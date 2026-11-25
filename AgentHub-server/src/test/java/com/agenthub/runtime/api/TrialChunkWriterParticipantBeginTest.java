package com.agenthub.runtime.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.service.ParticipantJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参与者 begin 帧的幂等与撞车留痕。所属域：runtime（出站层）。
 *
 * <p>为什么要有这组用例：{@code participantIdOf} 按「深度 + 父方案键 + 叶子键」拼 id，
 * 同一轮若撞车，第二张卡的 begin 会被 {@code putIfAbsent} 静默吞掉，页面只能等收尾回放补——
 * 现象就是「子专家后面才一起出现」。出站层不改 id、不补卡，但必须把这件事写进日志，
 * 否则线上永远只能靠猜。这里同时钉住「重复 begin 不多发帧」的老口径。
 *
 * <p>约束：假响应 + 内存写出，不起服务、不连库、不打真实模型。
 */
class TrialChunkWriterParticipantBeginTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 本次写出的 SSE 全文 */
    private StringWriter out;
    /** 挂到本类 logger 上的内存 appender，用来断言 WARN */
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    /**
     * 准备假响应与日志捕获。
     */
    @BeforeEach
    void setUp() {
        out = new StringWriter();
        logger = (Logger) LoggerFactory.getLogger(TrialChunkWriter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    /**
     * 摘掉 appender，避免污染同类其它用例。
     */
    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    /**
     * 用一个最小 HttpServletResponse 代理承接出站写出：只实现 SSE 真正会调的两个方法。
     *
     * @param writer 承接正文的 PrintWriter
     * @return 假响应
     */
    private static HttpServletResponse fakeResponse(PrintWriter writer) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWriter" -> writer;
                    case "isCommitted" -> false;
                    case "toString" -> "fake-sse-response";
                    default -> null;
                });
    }

    /**
     * 同一 id 重复 begin：只出一帧身份帧，并留一条 WARN（带 id / 本次与已登记的名字）。
     */
    @Test
    @DisplayName("同一 id 重复 begin 只出一帧，且撞车留 WARN")
    void duplicateBeginEmitsOnceAndWarns() {
        PrintWriter writer = new PrintWriter(out);
        TrialChunkWriter chunkWriter = new TrialChunkWriter(fakeResponse(writer), JSON);

        ParticipantView policy = ParticipantView.agent("p-1-enterprise-policy", "制度知识专家", "policy", 1);
        chunkWriter.beginParticipant(policy);
        // 撞车：同名同 id 再来一次，出站层不补帧（避免两张同名参与者卡）
        chunkWriter.beginParticipant(policy);
        chunkWriter.beginParticipant(ParticipantView.agent("p-1-enterprise-writer", "报告撰写专家", "writer", 1));
        writer.flush();

        assertEquals(2, countOf(out.toString(), "event: participant"), "两个参与者各一帧，重复 begin 不得多发");
        assertEquals(1, appender.list.size(), "撞车必须留一条日志");
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel(), "撞车按 WARN 记，不按 ERROR");
        String message = event.getFormattedMessage();
        assertTrue(message.contains("p-1-enterprise-policy"), "日志要带 id，才能定位是哪一位撞车");
        assertTrue(message.contains("制度知识专家"), "日志要带展示名，便于对照画面");
    }

    /**
     * 不同 id 各出一帧：正常路径不产生 WARN。
     */
    @Test
    @DisplayName("不同 id 各出一帧 begin，不产生撞车日志")
    void distinctIdsEmitEachOnce() {
        PrintWriter writer = new PrintWriter(out);
        TrialChunkWriter chunkWriter = new TrialChunkWriter(fakeResponse(writer), JSON);

        chunkWriter.beginParticipant(ParticipantView.agent("p-1-enterprise-policy", "制度知识专家", "policy", 1));
        chunkWriter.beginParticipant(ParticipantView.agent("p-1-enterprise-writer", "报告撰写专家", "writer", 1));
        writer.flush();

        assertEquals(2, countOf(out.toString(), "event: participant"));
        assertTrue(appender.list.isEmpty(), "正常路径不得有撞车日志");
    }

    /**
     * 两问：综合台与工作流都是 depth 0，子专家是 depth 1。
     * 出站 data 一次解析必须是对象；把落库字符串再编码一次则会变成字符串，前端收不到 begin。
     */
    @Test
    @DisplayName("participant 帧一次解析是对象，双重编码则只剩字符串")
    void beginFrameParsesOnceAsObject() throws Exception {
        PrintWriter writer = new PrintWriter(out);
        TrialChunkWriter chunkWriter = new TrialChunkWriter(fakeResponse(writer), JSON);
        ParticipantView scheme = ParticipantView.scheme("p-0-root-scheme", "综合台", "scheme", 0);
        ParticipantView workflow = new ParticipantView(
                "p-0-root-wf", "制度要点与报告", ParticipantView.KIND_WORKFLOW, "wf_report", 0);
        ParticipantView expert = ParticipantView.agent("p-1-scheme-policy", "制度知识专家", "policy", 1);
        chunkWriter.beginParticipant(scheme);
        chunkWriter.beginParticipant(workflow);
        chunkWriter.beginParticipant(expert);
        writer.flush();

        List<JsonNode> frames = participantFrames(out.toString());
        assertEquals(3, frames.size(), "三个 begin 各一帧");
        assertIdentity(frames.get(0), "综合台", "scheme", 0);
        assertIdentity(frames.get(1), "制度要点与报告", "workflow", 0);
        assertIdentity(frames.get(2), "制度知识专家", "agent", 1);

        // 落库正文本身是一份 JSON 对象；再交给会编码一次的写入器，解析结果就不再是对象
        String stored = ParticipantJson.payload(scheme, true);
        assertTrue(JSON.readTree(stored).isObject(), "落库正文一次解析仍是对象，回放靠这一次");
        JsonNode doubleEncoded = JSON.readTree(JSON.writeValueAsString(stored));
        assertTrue(doubleEncoded.isTextual(), "字符串再编码一次后，一次解析只能得到字符串");
        assertFalse(doubleEncoded.isObject());
    }

    /**
     * 一帧 begin 必须带 kind、depth，并且 begin 为 true。
     *
     * @param node  一次解析后的 data
     * @param name  展示名
     * @param kind  scheme / workflow / agent
     * @param depth 嵌套层级
     */
    private static void assertIdentity(JsonNode node, String name, String kind, int depth) {
        assertTrue(node.isObject(), "一次解析必须是对象，不能是被再包过的字符串");
        assertEquals(name, node.path("name").asText());
        assertEquals(kind, node.path("kind").asText());
        assertEquals(depth, node.path("depth").asInt(-1));
        assertTrue(node.path("begin").asBoolean(false));
    }

    /**
     * 抽出 participant 帧的 data，并按写出顺序做一次解析。
     *
     * @param sse 假响应里的全文
     * @return 每帧解析结果
     */
    private static List<JsonNode> participantFrames(String sse) throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        for (String block : sse.split("\n\n")) {
            String event = "";
            String data = "";
            for (String line : block.split("\n")) {
                if (line.startsWith("event: ")) {
                    event = line.substring("event: ".length()).trim();
                } else if (line.startsWith("data: ")) {
                    data = line.substring("data: ".length());
                }
            }
            if ("participant".equals(event) && !data.isEmpty()) {
                frames.add(JSON.readTree(data));
            }
        }
        return frames;
    }

    /**
     * 数一数某段固定文本在出站内容里出现几次。
     *
     * @param text   出站全文
     * @param needle 待数片段
     * @return 次数
     */
    private static int countOf(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count += 1;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}

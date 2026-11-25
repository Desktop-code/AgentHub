package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACL Adapter 单测的公共底座。所属域：runtime（ACL 实现）。
 * 职责：起一个 JDK 自带的本地假上游、记录收到的请求体、提供统一的流式收集断言辅助。
 * 约束：<b>零真实外网</b>——端口随机绑定在 127.0.0.1，不引用任何厂商域名或真实 Key；
 * 也不引 okhttp / MockWebServer（工程 classpath 上已同时存在 okhttp 4.12 与 5.3.2，再引替身会加剧冲突）。
 * 子类只关心「造厂商响应文本 + 断言 Unified 事件」。
 */
abstract class AclAdapterTestSupport {

    /** 测试共享的序列化器，与容器配置无关，够用即可 */
    protected static final ObjectMapper JSON = new ObjectMapper();
    /** 假 Key，仅用于断言「不出现在日志/文案里」，不指向任何真实凭证 */
    protected static final String FAKE_KEY = "sk-test-not-real";

    /** 假上游 */
    protected com.sun.net.httpserver.HttpServer server;
    /** 假上游根地址，形如 http://127.0.0.1:PORT */
    protected String baseUrl;
    /** 最近一次请求体，供断言出站映射 */
    protected final AtomicReference<String> receivedBody = new AtomicReference<>();
    /** 最近一次请求头快照，供断言各家族不同的鉴权头 */
    protected final AtomicReference<com.sun.net.httpserver.Headers> receivedHeaders = new AtomicReference<>();

    /**
     * 每个用例起一个本地假上游，端口随机，避免相互干扰。
     *
     * @throws IOException 端口绑定失败
     */
    @BeforeEach
    void startMockUpstream() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 关停假上游，释放端口。
     */
    @AfterEach
    void stopMockUpstream() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 注册一个一次性 SSE 响应，body 一次性写完再关闭。
     * 一次性写完对应「上游正常结束」；需要模拟截断时子类自行不加结束帧即可。
     *
     * @param path 请求路径（如 /api/v1/services/aigc/text-generation/generation）
     * @param body SSE 文本，需自带 data: 前缀与换行
     */
    protected void respondStreaming(String path, String body) {
        server.createContext(path, exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedHeaders.set(exchange.getRequestHeaders());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
            }
        });
    }

    /**
     * 注册一个「先推一帧再停住」的 SSE 响应，用于验证读空闲超时的 done 兜底。
     * 停住时间必须大于 Adapter 的 streamIdleTimeout，否则不会触发超时分支。
     *
     * @param path        请求路径
     * @param firstFrame  先写出的帧（可为空串，表示只发头不发数据）
     * @param stallMillis 停住多少毫秒后断开
     */
    protected void respondStalling(String path, String firstFrame, long stallMillis) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(firstFrame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                // 停住不写：模拟上游卡死，Adapter 必须靠 streamIdleTimeout 收口
                Thread.sleep(stallMillis);
                out.flush();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * 注册一个一次性 JSON 响应并记录请求体与请求头。
     *
     * @param path 请求路径
     * @param json 响应 JSON 文本
     */
    protected void respondJson(String path, String json) {
        server.createContext(path, exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedHeaders.set(exchange.getRequestHeaders());
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    /**
     * 注册一个非 2xx 响应，用于验证 error 摘要脱敏。
     *
     * @param path   请求路径
     * @param status HTTP 状态码
     * @param body   响应体原文（断言不得出现在前端文案里）
     */
    protected void respondError(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    /**
     * 构造指向假上游的 WebClient。
     *
     * @return 客户端
     */
    protected WebClient client() {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 发一个流式请求并同步收集全部事件。用阻塞收集是为了让断言简单；
     * 超时保护避免用例在缺 done 时永久挂起——这本身也是对「必须有 done」的检验。
     *
     * @param adapter 待测 Adapter
     * @return 全部事件，按到达顺序
     */
    protected List<UnifiedStreamEvent> collect(com.agenthub.runtime.model.acl.ModelProviderAdapter adapter) {
        UnifiedChatRequest request = UnifiedChatRequest.builder()
                .stream(true)
                .messages(List.of(UnifiedMessage.user("hi")))
                .build();
        List<UnifiedStreamEvent> events = adapter.chatStream(request)
                .collectList()
                .block(Duration.ofSeconds(10));
        assertTrue(events != null && !events.isEmpty(), "事件流不应为空");
        return events;
    }

    /**
     * 断言整条流「有且仅有一个 done，且位于末尾」。
     *
     * @param events 全部事件
     * @return 末尾的 done 事件
     */
    protected UnifiedStreamEvent assertSingleDoneAtEnd(List<UnifiedStreamEvent> events) {
        long doneCount = events.stream()
                .filter(e -> e.getType() == UnifiedStreamEventType.DONE)
                .count();
        assertTrue(doneCount == 1, "一次流必须且只能有一个 done，实际 " + doneCount);
        UnifiedStreamEvent last = events.get(events.size() - 1);
        assertTrue(last.getType() == UnifiedStreamEventType.DONE, "done 必须是最后一个事件");
        return last;
    }

    /**
     * 把某类事件的 content 按顺序拼起来，便于断言流式正文/思考的完整结果。
     *
     * @param events  全部事件
     * @param type    目标事件类型
     * @return 拼接后的文本
     */
    protected String concatContent(List<UnifiedStreamEvent> events, UnifiedStreamEventType type) {
        return events.stream()
                .filter(e -> e.getType() == type)
                .map(UnifiedStreamEvent::getContent)
                .filter(java.util.Objects::nonNull)
                .reduce("", String::concat);
    }

    /**
     * @param text 待校验文本
     * @return 是否可解析为 JSON 对象
     */
    protected static boolean isValidJsonObject(String text) {
        try {
            var node = JSON.readTree(text);
            return node != null && node.isObject();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 把一段原文转义成可放进 JSON 字符串的形式。
     *
     * @param raw 原文
     * @return 转义后的文本
     */
    protected static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 拼一行 SSE data 帧。
     *
     * @param json 帧 JSON（不带 data: 前缀）
     * @return 完整帧文本
     */
    protected static String dataFrame(String json) {
        return "data: " + json + "\n\n";
    }
}

package com.agenthub.runtime.api.sse;

import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.impl.OpenAICompatibleAdapter;
import com.agenthub.runtime.model.acl.impl.OpenAICompatibleConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adapter → 出站层的端到端兜底单测。所属域：runtime（出站层）。
 * 为什么需要它：Adapter 自己也有一套 done CAS，出站层还有一套。两套并存时最常见的缺陷是
 * <b>前端收到两帧 done</b>，导致重复收尾或重复刷新历史。本测试把真实 Adapter 接到出站写入器上，
 * 用本地假上游验证「整条链路只有一帧 done」。
 * 约束：假上游 + 内存 sink，不起服务、不连真实模型 API。
 */
class AdapterToSseDoneIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private com.sun.net.httpserver.HttpServer server;
    private String baseUrl;

    /**
     * 起本地假上游。
     */
    @BeforeEach
    void startServer() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * 关停假上游。
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 正常结束：Adapter 会在流末尾发 done，出站层不得再补一帧。
     */
    @Test
    @DisplayName("正常结束时整条链路只有一帧 done")
    void normalEndProducesSingleDone() {
        respondStreaming(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n");

        List<String> doneFrames = runPipelineAndFinish();
        assertEquals(1, doneFrames.size(), "正常结束时 done 只能有一帧");
    }

    /**
     * 上游截断：Adapter 补 done，出站层同样不重复。
     */
    @Test
    @DisplayName("上游截断无 [DONE] 时仍只有一帧 done")
    void truncatedStreamProducesSingleDone() {
        // 只有正文帧，既无 finish_reason 也无 [DONE]
        respondStreaming("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"半截\"}}]}\n\n");

        List<String> doneFrames = runPipelineAndFinish();
        assertEquals(1, doneFrames.size(), "截断流也必须只有一帧 done");
    }

    /**
     * 上游报错：Adapter 发 error + done，出站层补兜底不能产生第二帧 done。
     */
    @Test
    @DisplayName("上游 5xx 时仍只有一帧 done，且 error 先于 done")
    void upstreamErrorProducesSingleDoneWithErrorFirst() {
        server.createContext("/chat/completions", exchange -> {
            byte[] body = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        List<String> doneFrames = runPipelineAndFinish();
        assertEquals(1, doneFrames.size(), "错误路径也只能有一帧 done");

        // error 必须在 done 之前，否则前端已收尾就不再显示错误
        int errorIdx = sink.indexOf("error");
        int doneIdx = sink.indexOf("done");
        assertTrue(errorIdx >= 0, "应至少发过一次 error");
        assertTrue(errorIdx < doneIdx, "error 必须先于 done");
    }

    /**
     * 出站层的兜底不能依赖 Adapter：即使上游事件流里一帧 done 都没有
     * （模拟 Adapter 异常退出），Controller 的 ensureFinished 也必须补出来。
     */
    @Test
    @DisplayName("上游一帧 done 都没有时，出站兜底仍补出 done")
    void sinkLevelFallbackCoversMissingAdapterDone() {
        // 直接给出空事件流，模拟 Adapter 未产出任何 done
        writer.finish(null, null, "上游未产出结束帧");

        assertEquals(1, sink.countByEvent("done"));
        JsonNode payload = sink.lastPayloadOf("done");
        assertEquals("ERROR", payload.get("finishReason").asText(), "缺失原因不得伪装成功");
    }

    /**
     * 跑完整链路：Adapter 事件逐条喂给出站写入器，最后调用一次收尾兜底。
     *
     * @return 实际写出的 done 帧文本列表
     */
    private List<String> runPipelineAndFinish() {
        OpenAICompatibleConfig config = OpenAICompatibleConfig.builder()
                .providerName("test")
                .baseUrl(baseUrl)
                .apiKey("sk-test-not-real")
                .modelName("test-model")
                .streamIdleTimeout(Duration.ofSeconds(5))
                .build();
        OpenAICompatibleAdapter adapter = new OpenAICompatibleAdapter(
                config, WebClient.builder().baseUrl(baseUrl).build(), JSON);

        UnifiedChatRequest request = UnifiedChatRequest.builder()
                .stream(true)
                .messages(List.of(UnifiedMessage.user("hi")))
                .build();

        List<String> doneFrames = new ArrayList<>();
        // 逐条转发；出站层对 DONE 事件走 CAS，所以这里能直接验证是否重复
        adapter.chatStream(request)
                .doOnNext(event -> {
                    if (event != null) {
                        writer.emit(event);
                    }
                })
                .blockLast(Duration.ofSeconds(10));

        // Controller 的 finally 兜底
        writer.finish(null, null, null);
        for (Frame frame : sink.frames()) {
            if ("done".equals(frame.event())) {
                doneFrames.add(frame.json());
            }
        }
        return doneFrames;
    }

    /** 每个用例独立的出站写入器与 sink */
    private RecordingSink sink;
    private UnifiedSseWriter writer;

    /**
     * 初始化出站层，与用例同步准备。
     */
    @BeforeEach
    void initWriter() {
        sink = new RecordingSink();
        writer = new UnifiedSseWriter(sink, JSON);
        writer.bindSession(7L);
    }

    /**
     * 注册一个一次性 SSE 响应。
     */
    private void respondStreaming(String body) {
        server.createContext("/chat/completions", exchange -> {
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
     * 记录帧的内存 sink。
     */
    private static final class RecordingSink implements SseFrameSink {

        private final List<Frame> frames = new ArrayList<>();
        private final AtomicInteger writes = new AtomicInteger();
        private boolean completed;

        @Override
        public boolean write(String event, String json) {
            frames.add(new Frame(event, json));
            writes.incrementAndGet();
            return true;
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public boolean isBroken() {
            return false;
        }

        /**
         * @return 全部帧快照
         */
        List<Frame> frames() {
            return new ArrayList<>(frames);
        }

        /**
         * @return 指定 event 名的帧数
         */
        int countByEvent(String event) {
            return (int) frames.stream().filter(f -> event.equals(f.event)).count();
        }

        /**
         * @return 指定 event 名首次出现的下标，-1 表示没有
         */
        int indexOf(String event) {
            for (int i = 0; i < frames.size(); i++) {
                if (event.equals(frames.get(i).event)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * @return 指定 event 名最后一帧的 data
         */
        JsonNode lastPayloadOf(String event) {
            for (int i = frames.size() - 1; i >= 0; i--) {
                if (event.equals(frames.get(i).event)) {
                    try {
                        return JSON.readTree(frames.get(i).json);
                    } catch (Exception ex) {
                        throw new AssertionError("帧不是合法 JSON: " + frames.get(i).json, ex);
                    }
                }
            }
            throw new AssertionError("未找到 event=" + event);
        }
    }

    /**
     * 一帧记录。
     */
    private record Frame(String event, String json) {
    }
}

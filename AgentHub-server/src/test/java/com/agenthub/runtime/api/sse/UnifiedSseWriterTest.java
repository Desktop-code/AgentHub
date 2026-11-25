package com.agenthub.runtime.api.sse;

import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 出站层 done 兜底单测。所属域：runtime（出站层）。
 * 用内存 sink 精确记录每一帧，覆盖定稿 §4.2 的五条结束路径，重点验证：
 * 结束帧「恰好一次」、客户端取消仍尝试补帧、旧字段兼容形状正确。
 * 约束：纯内存，不起服务、不连真实模型。
 */
class UnifiedSseWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordingSink sink;
    private UnifiedSseWriter writer;

    /**
     * 每个用例用干净的 sink 与 writer。
     *
     * <p>用「手动定时泵」构造：真实泵会在断言之间抢跑，让「窗内只有一帧」的用例偶发失败。
     */
    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        writer = new UnifiedSseWriter(sink, JSON, SseBatchOptions.defaults(), System::nanoTime,
                new TextFlushPump.Manual());
        writer.bindSession(42L);
    }

    /**
     * 路径 1：上游已映射为 done，出站不得再补第二帧。
     * done 新旧事件名同为 done，因此只存在一帧。
     */
    @Test
    @DisplayName("上游已发 done 时不再补发，且恰好一帧")
    void upstreamDoneEmitsExactlyOnce() {
        writer.emit(UnifiedStreamEvent.done(UnifiedFinishReason.STOP, null));

        assertEquals(1, sink.countByEvent("done"), "done 只应有一帧（新旧同名合并）");
        assertTrue(sink.completed, "结束帧后必须收口连接");

        // 后续任何兜底调用都不能再产生帧
        writer.finish(UnifiedFinishReason.STOP, null, null);
        writer.finishError("重复调用");
        writer.finishCancelled();
        writer.finishTimeout("重复超时");
        assertEquals(1, sink.countByEvent("done"), "兜底路径不得重复发 done");
    }

    /**
     * 路径 2：上游流结束但缺结束标识，由收尾兜底补 done。
     */
    @Test
    @DisplayName("上游截断无结束标识时补 done（STOP 语义的兜底由调用方给定）")
    void truncatedStreamStillGetsDone() {
        writer.messageDelta("半截正文");

        // 流结束，调用方无条件收尾
        boolean written = writer.finish(UnifiedFinishReason.STOP, null, "上游未给结束标识");

        assertTrue(written);
        assertEquals(1, sink.countByEvent("done"));
        JsonNode payload = sink.lastPayloadOf("done");
        assertEquals("SUCCESS", payload.get("status").asText());
        assertEquals("STOP", payload.get("finishReason").asText());
    }

    /**
     * 路径 2 补充：截断应能用明确枚举表达，不能被伪装成正常成功。
     */
    @Test
    @DisplayName("降级结束原因映射为 FAILED 旧状态")
    void degradedReasonMapsToFailedStatus() {
        writer.finish(UnifiedFinishReason.LENGTH, null, "达到长度上限");

        JsonNode payload = sink.lastPayloadOf("done");
        assertEquals("FAILED", payload.get("status").asText());
        assertEquals("LENGTH", payload.get("finishReason").asText());
        assertTrue(payload.get("errorSummary").asText().contains("长度"));
    }

    /**
     * 路径 3：超时。
     */
    @Test
    @DisplayName("超时路径补 done 且原因为 TIMEOUT")
    void timeoutEmitsDone() {
        writer.finishTimeout("读超时 60s");

        assertEquals(1, sink.countByEvent("done"));
        JsonNode done = sink.lastPayloadOf("done");
        assertEquals("TIMEOUT", done.get("finishReason").asText());
        assertEquals("FAILED", done.get("status").asText());
    }

    /**
     * 路径 4：上游错误。先 error 再 done，且 error 只发一次。
     */
    @Test
    @DisplayName("上游错误时先 error 再 done，且 error 不重复")
    void upstreamErrorEmitsErrorThenDone() {
        writer.finishError("模型服务返回 HTTP 500");

        assertEquals(1, sink.countByEvent("error"), "error 只应发一次");
        assertEquals(1, sink.countByEvent("done"));
        // 顺序保证：error 必须在 done 之前，否则前端已收尾就不会再显示错误
        assertTrue(sink.indexOf("error") < sink.indexOf("done"));

        JsonNode done = sink.lastPayloadOf("done");
        assertEquals("ERROR", done.get("finishReason").asText());
        assertEquals("FAILED", done.get("status").asText());
    }

    /**
     * 路径 4 补充：已经通过 emit 发过 error 事件时，兜底不应再发一次。
     */
    @Test
    @DisplayName("已发过 error 事件时兜底不重复发 error")
    void errorNotDuplicatedWhenAlreadyEmitted() {
        writer.emit(UnifiedStreamEvent.error("上游中断", UnifiedFinishReason.ERROR));
        writer.finishError("上游中断");

        assertEquals(1, sink.countByEvent("error"), "error 不应重复下发");
        assertEquals(1, sink.countByEvent("done"));
    }

    /**
     * 路径 5：客户端取消。这是本刀的核心——出站层仍要真实尝试写一帧 done。
     * 断言方式：sink 记录到 done 帧，且 complete 被调用。
     */
    @Test
    @DisplayName("客户端取消时出站仍尝试发出 done 并收口")
    void clientCancelStillWritesDone() {
        writer.messageDelta("部分输出");

        boolean written = writer.finishCancelled();

        assertTrue(written, "取消路径必须真实尝试写出 done");
        assertEquals(1, sink.countByEvent("done"), "取消后必须有一帧 done");
        JsonNode done = sink.lastPayloadOf("done");
        assertEquals("CANCELLED", done.get("finishReason").asText());
        assertEquals("FAILED", done.get("status").asText());
        assertTrue(sink.completed, "取消后也要完成连接，否则容器一直挂着");
    }

    /**
     * 客户端彻底断开（写出失败）时，不能重复尝试，也不能抛异常给业务线程。
     */
    @Test
    @DisplayName("连接已断时取消兜底不重复尝试且不抛异常")
    void cancelOnBrokenConnectionIsSafe() {
        sink.failNextWrite = true;

        boolean written = writer.finishCancelled();

        assertFalse(written, "连接已断时写出应失败");
        // 即使写失败，CAS 也已占位，不允许再发第二次
        writer.finishCancelled();
        assertEquals(1, sink.attemptsOf("done"), "尝试次数应为一次，不应重复写");
    }

    /**
     * 兼容性：旧前端事件名仍可读，且 data 形状符合其解析器要求。
     * 合批后正文先留在缓冲里，由收尾（或任一控制帧）统一落地，故这里显式 finish 再断言。
     */
    @Test
    @DisplayName("旧事件名兼容写出：assistant/reasoning 为字符串，done 为对象")
    void legacyEventsRemainCompatible() {
        writer.messageDelta("你好");
        writer.thinkingDelta("先想一下");
        // 类型切换会先把上一段落地，收尾再把最后一段（reasoning）冲出去
        writer.finish(UnifiedFinishReason.STOP, null, null);

        // 新名与旧名都应出现（assistant↔message.delta 名字不同，故各一帧）
        assertEquals(1, sink.countByEvent(UnifiedStreamEventType.MESSAGE_DELTA.code()));
        assertEquals(1, sink.countByEvent("assistant"));
        assertEquals(1, sink.countByEvent("reasoning"));

        // 旧前端对 assistant 的 data 必须是 JSON 字符串（JSON.parse 后是 string）
        JsonNode assistantData = sink.lastPayloadOf("assistant");
        assertTrue(assistantData.isTextual(), "旧 assistant 的 data 必须是字符串");
        assertEquals("你好", assistantData.asText());

        // done 是同一帧承载新旧字段：必须是对象，前端读 status / sessionId，新前端读 finishReason
        JsonNode doneData = sink.lastPayloadOf("done");
        assertTrue(doneData.isObject(), "done 的 data 必须是对象");
        assertEquals(42L, doneData.get("sessionId").asLong());
        assertEquals("SUCCESS", doneData.get("status").asText());
        assertEquals("STOP", doneData.get("finishReason").asText());
    }

    /**
     * tool_call 不能被压成纯文本：必须保留 delta 与 completed 两种粒度。
     */
    @Test
    @DisplayName("tool_call.delta 与 tool_call.completed 均独立下发")
    void toolCallDeltaAndCompletedBothPreserved() {
        writer.emit(UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.TOOL_CALL_DELTA)
                .toolCallDelta(UnifiedToolCall.builder().id("call_1").name("weather").arguments("{\"ci").build())
                .build());
        writer.emit(UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.TOOL_CALL_COMPLETED)
                .toolCallDelta(UnifiedToolCall.builder().id("call_1").name("weather").arguments("{\"city\":\"北京\"}").build())
                .build());

        assertEquals(1, sink.countByEvent("tool_call.delta"));
        assertEquals(1, sink.countByEvent("tool_call.completed"));
        JsonNode completed = sink.lastPayloadOf("tool_call.completed");
        assertEquals("{\"city\":\"北京\"}", completed.get("toolCall").get("arguments").asText());
    }

    /**
     * 结束帧之后不能再写业务帧，否则前端会渲染到已收尾的轮次里。
     */
    @Test
    @DisplayName("done 之后拒绝任何业务帧")
    void nothingAfterDone() {
        writer.finish(UnifiedFinishReason.STOP, null, null);
        int before = sink.frames.size();

        assertFalse(writer.messageDelta("迟到的增量"));
        assertEquals(before, sink.frames.size(), "done 之后不应再写出任何帧");
    }

    /**
     * 用量随 done 一起下发，供前端与计费侧取用。
     */
    @Test
    @DisplayName("done 载荷携带 usage")
    void doneCarriesUsage() {
        writer.emit(UnifiedStreamEvent.builder()
                .type(UnifiedStreamEventType.USAGE)
                .usage(UnifiedUsage.builder().prompt(10).completion(5).total(15).build())
                .build());
        writer.finish(UnifiedFinishReason.STOP, null, null);

        JsonNode done = sink.lastPayloadOf("done");
        assertNotNull(done.get("usage"));
        assertEquals(15, done.get("usage").get("total").asInt());
    }

    /**
     * 缺少结束原因时不得伪装成成功。
     */
    @Test
    @DisplayName("原因为空时按 ERROR 兜底而不是 SUCCESS")
    void nullReasonFallsBackToError() {
        writer.finish(null, null, null);

        JsonNode done = sink.lastPayloadOf("done");
        assertEquals("ERROR", done.get("finishReason").asText());
        assertEquals("FAILED", done.get("status").asText());
    }

    // ---------- 测试替身 ----------

    /**
     * 记录所有写出帧的内存 sink，可模拟断连。
     */
    private static final class RecordingSink implements SseFrameSink {

        private final List<Frame> frames = new ArrayList<>();
        private boolean completed;
        /** 模拟「下一次写 done 失败」，用于断连用例 */
        private boolean failNextWrite;

        @Override
        public boolean write(String event, String json) {
            frames.add(new Frame(event, json));
            if (failNextWrite && "done".equals(event)) {
                failNextWrite = false;
                return false;
            }
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
         * @return 指定 event 名的帧数
         */
        int countByEvent(String event) {
            return (int) frames.stream().filter(f -> event.equals(f.event)).count();
        }

        /**
         * @return 指定 event 名的尝试次数（与 countByEvent 相同，语义上强调「尝试」）
         */
        int attemptsOf(String event) {
            return countByEvent(event);
        }

        /**
         * @return 指定 event 名首次出现的下标，不存在返回 -1
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
         * @return 指定 event 名最后一帧的 data 解析结果
         */
        JsonNode lastPayloadOf(String event) {
            for (int i = frames.size() - 1; i >= 0; i--) {
                if (event.equals(frames.get(i).event)) {
                    try {
                        return JSON.readTree(frames.get(i).json);
                    } catch (Exception ex) {
                        throw new AssertionError("帧 content 不是合法 JSON: " + frames.get(i).json, ex);
                    }
                }
            }
            throw new AssertionError("未找到 event=" + event + " 的帧");
        }
    }

    /**
     * 一帧记录。
     */
    private record Frame(String event, String json) {
    }
}

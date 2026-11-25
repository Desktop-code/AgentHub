package com.agenthub.runtime.api.sse;

import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正文增量合批单测。所属域：runtime（出站层）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>合批本身：连续同类型正文按时间窗 / 字节上限合并，类型切换与空串的边界；</li>
 *   <li>保序：控制帧（tool / phase / route / error / done）写出前必须先落地已攒的正文；</li>
 *   <li>不丢字：正常收尾、错误、取消三条路径都把尾字发出去，且都在 done 之前。</li>
 * </ol>
 * 约束：纯内存 sink + 可控时钟，不起服务、不连真实模型。
 */
class SseTextBatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 默认参数：50ms 时间窗 / 2048 字节 */
    private static final SseBatchOptions DEFAULT = new SseBatchOptions(50, 2048);

    private RecordingSink sink;
    private MutableClock clock;
    private UnifiedSseWriter writer;

    /**
     * 每个用例用干净的 sink、时钟与写入器。
     */
    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        clock = new MutableClock();
        writer = new UnifiedSseWriter(sink, JSON, DEFAULT, clock::nanos);
        writer.bindSession(9L);
    }

    /**
     * 同一时间窗内的连续正文必须合成一帧，而不是一 chunk 一帧。
     */
    @Test
    @DisplayName("窗内连续同类型正文合成一帧")
    void sameKindWithinWindowIsMerged() {
        writer.messageDelta("你");
        clock.advanceMillis(10);
        writer.messageDelta("好");
        clock.advanceMillis(10);
        writer.messageDelta("呀");
        // 收尾把最后一段冲出去
        writer.finish(UnifiedFinishReason.STOP, null, null);

        assertEquals(1, sink.countByEvent("assistant"), "三个 chunk 应合成一帧");
        assertEquals(1, sink.countByEvent(UnifiedStreamEventType.MESSAGE_DELTA.code()), "新名同样只有一帧");
        assertEquals("你好呀", sink.lastTextualOf("assistant"), "合批不得改字或乱序");
    }

    /**
     * 时间窗到期后新片段到达：先把上一段发出去，延迟被压在时间窗量级。
     */
    @Test
    @DisplayName("时间窗到期后新片段先发上一段")
    void windowExpiryFlushesPreviousSegment() {
        writer.messageDelta("第一段");
        clock.advanceMillis(60);
        writer.messageDelta("第二段");

        assertEquals(1, sink.countByEvent("assistant"), "到期时上一段应先落地");
        assertEquals("第一段", sink.lastTextualOf("assistant"));

        writer.finish(UnifiedFinishReason.STOP, null, null);
        assertEquals(2, sink.countByEvent("assistant"), "尾段由收尾 flush");
        assertEquals("第二段", sink.lastTextualOf("assistant"));
    }

    /**
     * 助手正文与思考不能混进同一帧：类型切换时先落地上一段。
     */
    @Test
    @DisplayName("类型切换先落地上一段，思考与正文不混帧")
    void kindSwitchFlushesFirst() {
        writer.messageDelta("正在答");
        writer.thinkingDelta("接着想");

        assertEquals(1, sink.countByEvent("assistant"), "正文段应先落地");
        assertEquals(0, sink.countByEvent("reasoning"), "思考段仍在缓冲");

        writer.finish(UnifiedFinishReason.STOP, null, null);
        assertEquals("正在答", sink.lastTextualOf("assistant"));
        assertEquals("接着想", sink.lastTextualOf("reasoning"));
    }

    /**
     * 字节上限是硬约束：攒够就立刻发，不必等时间窗，也不必等下一段到达。
     */
    @Test
    @DisplayName("字节上限触顶立刻发一帧")
    void byteLimitFlushesImmediately() {
        SseBatchOptions tiny = new SseBatchOptions(50, 6);
        UnifiedSseWriter tight = new UnifiedSseWriter(sink, JSON, tiny, clock::nanos);

        // 「一二三」是 9 个 UTF-8 字节，超过 6 字节上限
        tight.messageDelta("一二三");

        assertEquals(1, sink.countByEvent("assistant"), "触顶后应立即下发，不留在缓冲里");
        assertEquals("一二三", sink.lastTextualOf("assistant"));
    }

    /**
     * 单段超上限也不拆字：宁可一帧略大，也不能把代理对/多字节字符切两半。
     */
    @Test
    @DisplayName("单段超上限整段一帧，不拆字")
    void oversizedSingleDeltaStaysIntact() {
        SseBatchOptions tiny = new SseBatchOptions(50, 4);
        UnifiedSseWriter tight = new UnifiedSseWriter(sink, JSON, tiny, clock::nanos);

        tight.messageDelta("完整一句");

        assertEquals(1, sink.countByEvent("assistant"));
        assertEquals("完整一句", sink.lastTextualOf("assistant"));
    }

    /**
     * 工具卡、阶段、路由、图片文字等控制帧都必须在正文之后。
     */
    @Test
    @DisplayName("控制帧写出前先 flush 正文，卡片不会插进半句话")
    void controlFramesFlushPendingTextFirst() {
        writer.messageDelta("半句话");
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("line", "weather · 成功");
        writer.emitLegacy("tool", tool);

        assertEquals("半句话", sink.lastTextualOf("assistant"), "工具帧前应先落地正文");
        assertTrue(sink.indexOf("assistant") < sink.indexOf("tool"), "正文必须先于工具卡");

        writer.messageDelta("又说一句");
        writer.emitPhase("answering");
        assertEquals("又说一句", sink.lastTextualOf("assistant"));
        assertTrue(sink.indexOf("assistant") < sink.indexOf("phase"), "阶段帧不得越过正文");

        writer.messageDelta("再说一句");
        writer.emitRoute("路由到 A");
        assertEquals("再说一句", sink.lastTextualOf("assistant"));
        assertTrue(sink.indexOf("assistant") < sink.indexOf("route"), "路由帧不得越过正文");
    }

    /**
     * 正常收尾：尾字必须先于 done 写出，且内容完整。
     */
    @Test
    @DisplayName("收尾强制 flush，尾字完整且早于 done")
    void finishFlushesTailBeforeDone() {
        writer.messageDelta("最后");
        clock.advanceMillis(5);
        writer.messageDelta("半句");

        writer.finish(UnifiedFinishReason.STOP, null, null);

        assertEquals("最后半句", sink.lastTextualOf("assistant"));
        assertTrue(sink.indexOf("assistant") < sink.indexOf("done"), "尾字必须在 done 之前");
        assertEquals(1, sink.countByEvent("done"));
    }

    /**
     * 客户端取消：连接可能马上就断，尾字仍要先尝试发出。
     */
    @Test
    @DisplayName("取消路径也先 flush 尾字，再写 done")
    void cancelFlushesTail() {
        writer.messageDelta("取消前的字");

        writer.finishCancelled();

        assertEquals("取消前的字", sink.lastTextualOf("assistant"));
        assertTrue(sink.indexOf("assistant") < sink.indexOf("done"));
        assertEquals(1, sink.countByEvent("done"));
    }

    /**
     * 上游报错：正文必须在 error 之前，否则用户会以为回答被截断。
     */
    @Test
    @DisplayName("错误路径正文先于 error，error 先于 done")
    void errorFlushesTailBeforeErrorFrame() {
        writer.messageDelta("写了一半");

        writer.finishError("上游 500");

        assertEquals("写了一半", sink.lastTextualOf("assistant"));
        assertTrue(sink.indexOf("assistant") < sink.indexOf("error"), "正文必须先于 error");
        assertTrue(sink.indexOf("error") < sink.indexOf("done"));
    }

    /**
     * 收尾之后的增量一律丢弃，既不写帧也不留在缓冲里。
     */
    @Test
    @DisplayName("done 之后的增量不再产帧")
    void noFramesAfterDone() {
        writer.finish(UnifiedFinishReason.STOP, null, null);
        int before = sink.frames.size();

        assertFalse(writer.messageDelta("迟到的增量"));
        assertEquals(before, sink.frames.size(), "done 之后不得再写任何帧");
    }

    /**
     * 空片段与重复 flush 都是安全空操作，不会写出空帧。
     */
    @Test
    @DisplayName("空片段与空缓冲 flush 不产生帧")
    void emptyTextIsIgnored() {
        writer.messageDelta("");
        writer.thinkingDelta(null);
        SseTextBatcher batcher = new SseTextBatcher(DEFAULT, clock::nanos, (kind, text) -> {
            throw new AssertionError("不应写出任何帧");
        });
        batcher.flush();

        assertTrue(sink.frames.isEmpty(), "空内容不得产生帧");
        assertFalse(batcher.hasPending());
    }

    // ---------- 定时 flush（流畅度刀 P0-B1）----------

    /**
     * 时间窗到点即发：模型停顿、没有下一段到达时，尾包也必须出去。
     */
    @Test
    @DisplayName("定时泵到点即发，不再等下一段到达")
    void timedPumpFlushesWithoutNextDelta() {
        TextFlushPump.Manual pump = new TextFlushPump.Manual();
        UnifiedSseWriter timed = new UnifiedSseWriter(sink, JSON, DEFAULT, clock::nanos, pump);

        timed.messageDelta("停在这里");

        assertEquals(0, sink.countByEvent("assistant"), "刚收下时仍在缓冲，不该立刻发");
        assertEquals(1, pump.pending(), "应收下后登记一次窗口到点的 flush");

        pump.fire();

        assertEquals(1, sink.countByEvent("assistant"), "到点必须把尾包发出去");
        assertEquals("停在这里", sink.lastTextualOf("assistant"));
    }

    /**
     * 同一段缓冲只登记一次：模型高速输出时任务不会堆积。
     */
    @Test
    @DisplayName("同一段缓冲只登记一次定时 flush")
    void timedPumpSchedulesOncePerBuffer() {
        TextFlushPump.Manual pump = new TextFlushPump.Manual();
        UnifiedSseWriter timed = new UnifiedSseWriter(sink, JSON, DEFAULT, clock::nanos, pump);

        timed.messageDelta("一");
        timed.messageDelta("二");
        timed.messageDelta("三");

        assertEquals(1, pump.pending(), "连续同类型正文只登记一次任务");

        pump.fire();
        assertEquals("一二三", sink.lastTextualOf("assistant"));
    }

    /**
     * 已收尾之后到点不再写帧：迟到帧会污染下一轮或让前端重复收尾。
     */
    @Test
    @DisplayName("收尾后到点任务不写任何帧")
    void timedPumpSilentAfterDone() {
        TextFlushPump.Manual pump = new TextFlushPump.Manual();
        UnifiedSseWriter timed = new UnifiedSseWriter(sink, JSON, DEFAULT, clock::nanos, pump);

        timed.messageDelta("结尾");
        timed.finish(UnifiedFinishReason.STOP, null, null);
        int before = sink.frames.size();

        pump.fire();

        assertEquals(before, sink.frames.size(), "done 之后到点任务不得再写帧");
    }

    // ---------- 测试替身 ----------

    /**
     * 记录所有写出帧的内存 sink。
     */
    private static final class RecordingSink implements SseFrameSink {

        private final List<Frame> frames = new ArrayList<>();
        private boolean completed;

        @Override
        public boolean write(String event, String json) {
            frames.add(new Frame(event, json));
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
         * @return 指定事件的帧数
         */
        int countByEvent(String event) {
            return (int) frames.stream().filter(f -> event.equals(f.event())).count();
        }

        /**
         * @return 指定事件首次出现的下标，-1 表示没有
         */
        int indexOf(String event) {
            for (int i = 0; i < frames.size(); i++) {
                if (event.equals(frames.get(i).event())) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * @return 指定事件最后一帧的文本载荷（旧前端 assistant / reasoning 是 JSON 字符串）
         */
        String lastTextualOf(String event) {
            for (int i = frames.size() - 1; i >= 0; i--) {
                if (!event.equals(frames.get(i).event())) {
                    continue;
                }
                try {
                    JsonNode node = JSON.readTree(frames.get(i).json());
                    return node.isTextual() ? node.asText() : node.toString();
                } catch (Exception ex) {
                    throw new AssertionError("帧不是合法 JSON: " + frames.get(i).json(), ex);
                }
            }
            throw new AssertionError("未找到 event=" + event + " 的帧");
        }
    }

    /**
     * 可控纳秒时钟：让时间窗判定在单测里可重复，不依赖真实 sleep。
     */
    private static final class MutableClock {

        private long nanos = 1_000_000_000L;

        /**
         * @return 当前纳秒数
         */
        long nanos() {
            return nanos;
        }

        /**
         * @param millis 前进的毫秒数
         */
        void advanceMillis(long millis) {
            nanos += millis * 1_000_000L;
        }
    }

    /**
     * 一帧记录。
     */
    private record Frame(String event, String json) {
    }
}

package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.AgentChoice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化选项的后端兜底整理。所属域：runtime 的 domain。
 *
 * <p>这一层是「模型没调工具」时的最后一道：只在边界全部满足时出站一次，
 * 输出不合法 / 出站抛错都按「不出 chips」处理，绝不影响已经完成的这一轮回答。
 */
class WorkbenchChoiceCollectorTest {

    private static final String VALID = "{\"question\":\"建议用什么前端技术栈？\","
            + "\"options\":[{\"id\":\"vue3\",\"label\":\"Vue3 + Vite + TS\"},"
            + "{\"id\":\"react\",\"label\":\"React 19 + TS\"}]}";

    private static final String LONG_ANSWER =
            "如果团队偏 Vue 生态，推荐 Vue3 + Vite + TS；如果更看重生态与招聘，React 19 + TS 也很稳。";

    @Test
    void collectsWhenModelGaveNoToolCall() {
        StubStructurer structurer = new StubStructurer(VALID);
        WorkbenchChoiceCollector collector = new WorkbenchChoiceCollector(structurer);

        AgentChoice choice = collector.collect(LONG_ANSWER, 7L, 9L, 1L, 2L);

        assertNotNull(choice);
        assertEquals(2, choice.options().size());
        assertEquals("vue3", choice.options().get(0).id());
        assertEquals(1, structurer.calls.size(), "边界满足时只出站一次");
        assertEquals(9L, structurer.calls.get(0).modelInstanceId(), "必须用叶子专家自己的模型实例");
        assertEquals(LONG_ANSWER, structurer.calls.get(0).source(), "整理送的是终答正文");
    }

    @Test
    void skipsWhenStructurerMissing() {
        WorkbenchChoiceCollector collector = new WorkbenchChoiceCollector(null);
        assertNull(collector.collect(LONG_ANSWER, 7L, 9L, 1L, 2L), "没装配出站能力时直接放弃");
    }

    @Test
    void skipsWhenAnswerTooShortOrBlank() {
        StubStructurer structurer = new StubStructurer(VALID);
        WorkbenchChoiceCollector collector = new WorkbenchChoiceCollector(structurer);

        assertNull(collector.collect(null, 7L, 9L, 1L, 2L));
        assertNull(collector.collect("   ", 7L, 9L, 1L, 2L));
        assertNull(collector.collect("可以。", 7L, 9L, 1L, 2L), "过短的终答不可能承载 2~6 个候选");
        assertTrue(structurer.calls.isEmpty(), "边界不满足时不得产生出站");
    }

    @Test
    void skipsWhenModelInstanceMissing() {
        StubStructurer structurer = new StubStructurer(VALID);
        WorkbenchChoiceCollector collector = new WorkbenchChoiceCollector(structurer);

        assertNull(collector.collect(LONG_ANSWER, 7L, null, 1L, 2L));
        assertNull(collector.collect(LONG_ANSWER, null, 9L, 1L, 2L));
        assertTrue(structurer.calls.isEmpty());
    }

    @Test
    void returnsNullWhenOutputIsNotValidStructure() {
        WorkbenchChoiceCollector collector = new WorkbenchChoiceCollector(new StubStructurer("我建议 Vue3。"));
        assertNull(collector.collect(LONG_ANSWER, 7L, 9L, 1L, 2L), "整理不成就不出 chip 栏");

        WorkbenchChoiceCollector oneOption = new WorkbenchChoiceCollector(
                new StubStructurer("{\"question\":\"q\",\"options\":[{\"id\":\"a\",\"label\":\"A\"}]}"));
        assertNull(oneOption.collect(LONG_ANSWER, 7L, 9L, 1L, 2L));
    }

    @Test
    void swallowsOutboundFailure() {
        WorkbenchChoiceCollector collector = new WorkbenchChoiceCollector(new StubStructurer(null, true));
        // 出站失败不能让整轮判失败：这里既不抛，也不要半份结构
        assertNull(collector.collect(LONG_ANSWER, 7L, 9L, 1L, 2L));
    }

    @Test
    void truncatesOverlongAnswerBeforeOutbound() {
        StubStructurer structurer = new StubStructurer(VALID);
        WorkbenchChoiceCollector collector = new WorkbenchChoiceCollector(structurer);

        String huge = "技".repeat(9000);
        assertNotNull(collector.collect(huge, 7L, 9L, 1L, 2L));
        assertEquals(1, structurer.calls.size());
        String sent = structurer.calls.get(0).source();
        assertTrue(sent.length() <= 6000, "送进整理模型的正文必须截断，实际 " + sent.length());
        assertFalse(sent.isEmpty());
    }

    /** 一次出站入参的快照，便于断言「用哪个模型实例、送了什么正文」。 */
    private record Call(long agentId, Long sessionId, long userId, long modelInstanceId,
                        String instruction, String source) {
    }

    /** 记录出站入参的假 structurer；可指定返回值或抛出。 */
    private static final class StubStructurer implements ChoiceStructurer {

        private final String raw;
        private final boolean fail;
        private final List<Call> calls = new ArrayList<>();

        private StubStructurer(String raw) {
            this(raw, false);
        }

        private StubStructurer(String raw, boolean fail) {
            this.raw = raw;
            this.fail = fail;
        }

        @Override
        public String complete(long agentId, Long sessionId, long userId, long modelInstanceId,
                               String instruction, String source) {
            calls.add(new Call(agentId, sessionId, userId, modelInstanceId, instruction, source));
            if (fail) {
                throw new IllegalStateException("整理模型超时");
            }
            return raw;
        }
    }
}

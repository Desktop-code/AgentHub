package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.TrialTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 没有思考正文时不能产生 reasoning；工具留下名称、脱敏参数、成败和结果。
 */
class TrialTraceAccumulatorTest {

    /**
     * 空白思考和未出现的工具都不落步骤。
     */
    @Test
    void blankThinkingDoesNotCreateReasoning() {
        TrialTraceAccumulator accumulator = new TrialTraceAccumulator();
        accumulator.onThinkingDelta(" \n\t");
        accumulator.onThinkingEnd();
        assertTrue(accumulator.finish().isEmpty());
    }

    /**
     * 思考在工具前面，结果正文不混进工具名。
     */
    @Test
    void thinkingThenToolKeepsOrder() {
        TrialTraceAccumulator accumulator = new TrialTraceAccumulator();
        accumulator.onThinkingDelta("先查天气");
        accumulator.onToolStart("call-1", "weather");
        accumulator.onToolArgDelta("call-1", "weather", "{\"city\":\"北京\",\"api_key\":\"sk-live-abcdef\"}");
        accumulator.onToolResultDelta("call-1", "weather", "晴，api_key 不在这里");
        accumulator.onToolResultEnd("call-1", "weather", true);

        List<TrialTrace> traces = accumulator.finish();
        assertEquals(2, traces.size());
        assertEquals(EventType.REASONING, traces.get(0).getType());
        assertEquals("先查天气", traces.get(0).getContent());
        assertEquals(EventType.TOOL, traces.get(1).getType());
        assertEquals("weather", traces.get(1).getName());
        assertTrue(traces.get(1).isSuccess());
        assertFalse(traces.get(1).getArguments().contains("sk-live"));
        assertTrue(traces.get(1).getArguments().contains("北京"));
        assertEquals("晴，api_key 不在这里", traces.get(1).getContent());
    }

    /**
     * 只有工具、没有思考时，列表里不出现 reasoning。未结束的调用记失败。
     */
    @Test
    void toolWithoutThinkingIsNotFakeReasoning() {
        TrialTraceAccumulator accumulator = new TrialTraceAccumulator();
        accumulator.onToolStart("call-2", "browser");
        accumulator.onToolResultEnd("call-2", "browser", false);

        TrialTraceAccumulator unfinished = new TrialTraceAccumulator();
        unfinished.onToolStart("call-3", "calc");

        List<TrialTrace> failed = accumulator.finish();
        List<TrialTrace> open = unfinished.finish();
        assertEquals(1, failed.size());
        assertEquals(EventType.TOOL, failed.get(0).getType());
        assertFalse(failed.get(0).isSuccess());
        assertFalse(open.get(0).isSuccess());
        assertTrue(failed.stream().noneMatch(item -> item.getType() == EventType.REASONING));
        assertTrue(open.stream().noneMatch(item -> item.getType() == EventType.REASONING));
    }
}

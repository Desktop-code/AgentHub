package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.PlanStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划输出解析：序号重排、步数硬顶、容错写法。
 */
class PlanOutputParserTest {

    @Test
    void parsesOrderedArrayAndRenumbersSeq() {
        String reply = "[{\"seq\":3,\"title\":\"查天气\",\"detail\":\"北京\"},"
                + "{\"seq\":7,\"title\":\"给建议\"}]";
        List<PlanStep> steps = PlanOutputParser.parse(reply, 8);
        assertEquals(2, steps.size());
        // 模型给的 seq 不可信：一律按出现顺序从 1 连续编号，保证执行顺序
        assertEquals(1, steps.get(0).seq());
        assertEquals("查天气", steps.get(0).title());
        assertEquals("北京", steps.get(0).detail());
        assertEquals(2, steps.get(1).seq());
        assertEquals("", steps.get(1).detail());
    }

    @Test
    void trimsToMaxSteps() {
        String reply = "[{\"title\":\"a\"},{\"title\":\"b\"},{\"title\":\"c\"}]";
        assertEquals(2, PlanOutputParser.parse(reply, 2).size());
        // 上限小于 1 时按 1 处理，不返回空计划
        assertEquals(1, PlanOutputParser.parse(reply, 0).size());
    }

    @Test
    void toleratesCodeFenceAndAlternateFieldNames() {
        String reply = "好的，计划如下：\n```json\n"
                + "[{\"step\":\"第一步\",\"description\":\"说明\"}]\n```\n请批准。";
        List<PlanStep> steps = PlanOutputParser.parse(reply, 8);
        assertEquals(1, steps.size());
        assertEquals("第一步", steps.get(0).title());
        assertEquals("说明", steps.get(0).detail());
    }

    @Test
    void singleObjectIsAccepted() {
        List<PlanStep> steps = PlanOutputParser.parse("{\"title\":\"只有一步\"}", 8);
        assertEquals(1, steps.size());
        assertEquals(1, steps.get(0).seq());
    }

    @Test
    void invalidOrEmptyReturnsEmpty() {
        assertTrue(PlanOutputParser.parse(null, 8).isEmpty());
        assertTrue(PlanOutputParser.parse("我不打算给出计划", 8).isEmpty());
        assertTrue(PlanOutputParser.parse("[]", 8).isEmpty());
        // 标题全空的元素必须被丢弃，不能产出空步骤
        assertTrue(PlanOutputParser.parse("[{\"detail\":\"没有标题\"}]", 8).isEmpty());
    }
}

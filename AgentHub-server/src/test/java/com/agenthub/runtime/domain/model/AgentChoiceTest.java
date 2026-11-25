package com.agenthub.runtime.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化选项的边界校验。所属域：runtime 的 domain。
 *
 * <p>覆盖工具调用、后端整理、旧机器块三条来源共用的 {@link AgentChoice#of} 口径：
 * 数量 2~6、id / label 非空且 id 不重复，任何一条不合格都必须整份作废。
 */
class AgentChoiceTest {

    @Test
    void acceptsTwoToSixOptions() {
        AgentChoice two = AgentChoice.of(" 选哪个？ ", options("a", "b"));
        assertNotNull(two);
        assertEquals("选哪个？", two.question(), "问题应去掉首尾空白");
        assertEquals(2, two.options().size());
        assertTrue(two.usable());

        AgentChoice six = AgentChoice.of("选哪个？", options("a", "b", "c", "d", "e", "f"));
        assertNotNull(six);
        assertEquals(6, six.options().size());
    }

    @Test
    void rejectsOutOfRangeCount() {
        assertNull(AgentChoice.of("一个选项不算拍板", options("a")), "少于 2 项应作废");
        assertNull(AgentChoice.of("太多", options("a", "b", "c", "d", "e", "f", "g")), "超过 6 项应作废");
        assertNull(AgentChoice.of("空表", List.of()), "空表应作废");
        assertNull(AgentChoice.of("null 表", null), "null 表应作废");
    }

    @Test
    void trimsFieldsAndRejectsBlankOnes() {
        AgentChoice trimmed = AgentChoice.of("q", List.of(
                new AgentChoice.Option(" vue3 ", " Vue3 + Vite "),
                new AgentChoice.Option("react", "React")));
        assertNotNull(trimmed);
        assertEquals("vue3", trimmed.options().get(0).id());
        assertEquals("Vue3 + Vite", trimmed.options().get(0).label());

        assertNull(AgentChoice.of("q", List.of(
                new AgentChoice.Option("", "只有文案"),
                new AgentChoice.Option("b", "B"))), "id 为空应作废");
        assertNull(AgentChoice.of("q", List.of(
                new AgentChoice.Option("a", "  "),
                new AgentChoice.Option("b", "B"))), "label 为空应作废");
        List<AgentChoice.Option> withNull = new ArrayList<>();
        withNull.add(new AgentChoice.Option("a", "A"));
        withNull.add(null);
        assertNull(AgentChoice.of("q", withNull), "含 null 项应作废");
    }

    @Test
    void rejectsDuplicateIdIgnoringCase() {
        assertNull(AgentChoice.of("q", options("vue3", "VUE3")), "id 重复（忽略大小写）应作废");
    }

    @Test
    void findIsCaseInsensitiveAndToleratesBlank() {
        AgentChoice choice = AgentChoice.of("q", options("Vue3", "react"));
        assertNotNull(choice);
        assertTrue(choice.find("vue3").isPresent(), "大小写不同也应命中");
        assertTrue(choice.find("  react ").isPresent(), "首尾空白应容忍");
        assertTrue(choice.find("svelte").isEmpty());
        assertTrue(choice.find(null).isEmpty());
        assertTrue(choice.find("  ").isEmpty());
    }

    @Test
    void nullOptionsIsNormalizedToEmptyButNotUsable() {
        AgentChoice empty = new AgentChoice("q", null);
        assertNotNull(empty.options());
        assertFalse(empty.usable(), "空列表不构成可点选项");
    }

    /** 按给定 id 生成一组文案与 id 相同的选项（label 非空即可通过校验）。 */
    private static List<AgentChoice.Option> options(String... ids) {
        List<AgentChoice.Option> list = new ArrayList<>();
        for (String id : ids) {
            list.add(new AgentChoice.Option(id, "文案-" + id));
        }
        return list;
    }
}

package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.AgentChoice;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化选项的 JSON 解析与序列化。所属域：runtime 的 domain。
 *
 * <p>重点覆盖两条来源的容错：模型偶发把 JSON 包在围栏或解释文字里仍能取出本体，
 * 但本体不合规（数量越界、id 重复、缺字段）时必须整份作废，绝不半信半疑放行。
 */
class WorkbenchChoiceJsonTest {

    private static final String VALID = "{\"question\":\"建议用什么前端技术栈？\","
            + "\"options\":[{\"id\":\"vue3\",\"label\":\"Vue3 + Vite + TS\"},"
            + "{\"id\":\"react\",\"label\":\"React 19 + TS\"}]}";

    @Test
    void parsesPlainJson() {
        Optional<AgentChoice> parsed = WorkbenchChoiceJson.parseJson(VALID);
        assertTrue(parsed.isPresent());
        assertEquals("建议用什么前端技术栈？", parsed.get().question());
        assertEquals(2, parsed.get().options().size());
        assertEquals("vue3", parsed.get().options().get(0).id());
    }

    @Test
    void toleratesFenceAndExplanationAroundJson() {
        String wrapped = "好的，我整理成选项：\n```json\n" + VALID + "\n```\n以上。";
        Optional<AgentChoice> parsed = WorkbenchChoiceJson.parseJson(wrapped);
        assertTrue(parsed.isPresent(), "模型把 JSON 包在说明文字里也应能取出");
        assertEquals(2, parsed.get().options().size());
    }

    @Test
    void rejectsInvalidStructures() {
        assertTrue(WorkbenchChoiceJson.parseJson(null).isEmpty());
        assertTrue(WorkbenchChoiceJson.parseJson("").isEmpty());
        assertTrue(WorkbenchChoiceJson.parseJson("这里没有 JSON").isEmpty());
        // 只有 1 个选项：不构成拍板
        assertTrue(WorkbenchChoiceJson.parseJson(
                "{\"question\":\"q\",\"options\":[{\"id\":\"a\",\"label\":\"A\"}]}").isEmpty());
        // 缺 options
        assertTrue(WorkbenchChoiceJson.parseJson("{\"question\":\"q\"}").isEmpty());
        // options 不是数组
        assertTrue(WorkbenchChoiceJson.parseJson("{\"question\":\"q\",\"options\":\"a,b\"}").isEmpty());
        // 模型输出被截断
        assertTrue(WorkbenchChoiceJson.parseJson("{\"question\":\"q\",\"options\":[{\"id\":\"a\"").isEmpty());
    }

    @Test
    void writeSkipsUnusableAndProducesCompactJson() {
        assertEquals("", WorkbenchChoiceJson.write(null));
        // 单选项在构造阶段就被作废，这里确认「拿不到结构就不写事件正文」
        assertEquals(null, AgentChoice.of("q", java.util.List.of(new AgentChoice.Option("a", "A"))));

        AgentChoice choice = WorkbenchChoiceJson.parseJson(VALID).orElseThrow();
        String json = WorkbenchChoiceJson.write(choice);
        assertTrue(json.contains("\"question\":"), json);
        assertTrue(json.contains("\"vue3\""), json);
        assertFalse(json.contains("\n"), "事件正文应为紧凑 JSON：" + json);
        // 写出去的内容必须能被自己读回来，保证前后端与落库口径一致
        assertTrue(WorkbenchChoiceJson.parseJson(json).isPresent());
    }

    @Test
    void legacyFenceBlockIsStrippedFromBody() {
        String raw = "Vue3 更合适。\n\n```" + WorkbenchChoiceJson.FENCE_LANG + "\n" + VALID + "\n```\n";
        Optional<WorkbenchChoiceJson.Parsed> parsed = WorkbenchChoiceJson.parse(raw);
        assertTrue(parsed.isPresent(), "历史会话的机器块仍要能读");
        assertEquals("Vue3 更合适。", parsed.get().text(), "机器块必须从正文里摘掉");
        assertEquals(2, parsed.get().choice().options().size());
    }

    @Test
    void legacyFenceWithWrongLanguageIsIgnored() {
        String raw = "Vue3 更合适。\n\n```agenthub-choice-extra\n" + VALID + "\n```\n";
        assertTrue(WorkbenchChoiceJson.parse(raw).isEmpty(), "同前缀语言标记不应被误认");
        assertEquals(raw.trim(), WorkbenchChoiceJson.strip(raw));
    }
}

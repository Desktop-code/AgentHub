package com.agenthub.eval.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跑批配置合并与 openjudge 开关解析。所属域：eval。纯函数单测，不出站。
 */
class EvalRunConfigTest {

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 对象深合并：覆盖方优先，未覆盖的基线字段保留；数组整体替换不合并。
     */
    @Test
    void mergeIsDeepWithOverrideWinning() {
        ObjectNode base = json.createObjectNode();
        base.put("max_parallel", 1);
        base.putObject("openjudge").put("enabled", false).put("timeout_ms", 60000);
        base.putArray("graders").add("A");

        ObjectNode override = json.createObjectNode();
        override.put("max_parallel", 2);
        override.putObject("openjudge").put("enabled", true);
        override.putArray("graders").add("B");

        JsonNode merged = EvalRunConfig.merge(base, override);
        assertEquals(2, merged.get("max_parallel").asInt());
        assertTrue(merged.get("openjudge").get("enabled").asBoolean());
        assertEquals(60000, merged.get("openjudge").get("timeout_ms").asInt(), "未被覆盖的子字段必须保留");
        assertEquals(1, merged.get("graders").size(), "数组整体替换");
        assertEquals("B", merged.get("graders").get(0).asText());

        // 合并不得改写入参（基线被 deepCopy 保护）
        assertEquals(1, base.get("max_parallel").asInt());
    }

    /**
     * 两侧空：返回 null 而不是空对象，便于上层判空口径统一。
     */
    @Test
    void mergeReturnsNullWhenBothBlank() {
        assertNull(EvalRunConfig.merge(null, null));
        assertNull(EvalRunConfig.merge(json.nullNode(), null));
    }

    /**
     * openjudge 开关：缺省、结构不对、类型不对一律关闭；布尔与 0/1 都认。
     */
    @Test
    void openjudgeEnabledDefaultsToFalse() {
        assertFalse(EvalRunConfig.openjudgeEnabled(null));
        assertFalse(EvalRunConfig.openjudgeEnabled(json.createObjectNode()));
        // 有 openjudge 节点但没写 enabled
        assertFalse(EvalRunConfig.openjudgeEnabled(configWithOpenjudge(null)));
        // 字符串 "true" 不认，按关闭处理而不是让建跑失败
        assertFalse(EvalRunConfig.openjudgeEnabled(configWithOpenjudge(
                JsonNodeFactory.instance.textNode("true"))));

        assertTrue(EvalRunConfig.openjudgeEnabled(configWithOpenjudge(
                JsonNodeFactory.instance.booleanNode(true))));
        assertTrue(EvalRunConfig.openjudgeEnabled(configWithOpenjudge(
                JsonNodeFactory.instance.numberNode(1))));
        assertFalse(EvalRunConfig.openjudgeEnabled(configWithOpenjudge(
                JsonNodeFactory.instance.booleanNode(false))));
        assertFalse(EvalRunConfig.openjudgeEnabled(configWithOpenjudge(
                JsonNodeFactory.instance.numberNode(0))));
    }

    /**
     * 打分模型解析：数字 / 数字字符串认；缺失、null、空串、乱码一律 null（不猜默认模型）。
     */
    @Test
    void judgeModelInstanceIdParsesOnlyExplicitValues() {
        assertNull(EvalRunConfig.judgeModelInstanceId(null));
        assertNull(EvalRunConfig.judgeModelInstanceId(json.createObjectNode()));

        ObjectNode numeric = json.createObjectNode();
        numeric.putObject("openjudge").put("judge_model_instance_id", 42L);
        assertEquals(Long.valueOf(42L), EvalRunConfig.judgeModelInstanceId(numeric));

        ObjectNode textual = json.createObjectNode();
        textual.putObject("openjudge").put("judge_model_instance_id", "42");
        assertEquals(Long.valueOf(42L), EvalRunConfig.judgeModelInstanceId(textual));

        ObjectNode nullValue = json.createObjectNode();
        nullValue.putObject("openjudge").putNull("judge_model_instance_id");
        assertNull(EvalRunConfig.judgeModelInstanceId(nullValue));

        ObjectNode blank = json.createObjectNode();
        blank.putObject("openjudge").put("judge_model_instance_id", "  ");
        assertNull(EvalRunConfig.judgeModelInstanceId(blank));

        ObjectNode garbage = json.createObjectNode();
        garbage.putObject("openjudge").put("judge_model_instance_id", "abc");
        assertNull(EvalRunConfig.judgeModelInstanceId(garbage));
    }

    /**
     * judgeEnabled：必须「开关开 + 有模型」；只有开关没有模型按关闭处理。
     */
    @Test
    void judgeEnabledRequiresBothSwitchAndModel() {
        ObjectNode onlySwitch = json.createObjectNode();
        onlySwitch.putObject("openjudge").put("enabled", true);
        assertFalse(EvalRunConfig.judgeEnabled(onlySwitch), "无模型不得算启用");

        ObjectNode both = onlySwitch.deepCopy();
        ((ObjectNode) both.get("openjudge")).put("judge_model_instance_id", 7L);
        assertTrue(EvalRunConfig.judgeEnabled(both));

        ObjectNode modelButOff = json.createObjectNode();
        modelButOff.putObject("openjudge").put("enabled", false).put("judge_model_instance_id", 7L);
        assertFalse(EvalRunConfig.judgeEnabled(modelButOff));
    }

    /**
     * 钉死选择：选了 → enabled=true + id；不选 → enabled=false + id=null，
     * 且覆盖套件/全局基线（不允许下层把打分反开），不改入参。
     */
    @Test
    void pinJudgeSelectionOverridesLowerTiers() {
        ObjectNode suiteDefault = json.createObjectNode();
        suiteDefault.putObject("openjudge")
                .put("enabled", true)
                .put("judge_model_instance_id", 111L);
        suiteDefault.put("max_parallel", 1);

        ObjectNode selected = EvalRunConfig.pinJudgeSelection(suiteDefault, 222L);
        assertTrue(selected.get("openjudge").get("enabled").asBoolean());
        assertEquals(Long.valueOf(222L), EvalRunConfig.judgeModelInstanceId(selected), "本 run 选择优先于套件默认");

        ObjectNode notSelected = EvalRunConfig.pinJudgeSelection(suiteDefault, null);
        assertFalse(notSelected.get("openjudge").get("enabled").asBoolean());
        assertNull(EvalRunConfig.judgeModelInstanceId(notSelected));
        assertTrue(notSelected.get("openjudge").get("judge_model_instance_id").isNull());
        assertEquals(1, notSelected.get("max_parallel").asInt(), "其它字段保留");

        // 入参不被改写
        assertTrue(suiteDefault.get("openjudge").get("enabled").asBoolean());
        assertEquals(Long.valueOf(111L), EvalRunConfig.judgeModelInstanceId(suiteDefault));

        // 空基线也能钉
        ObjectNode fromNull = EvalRunConfig.pinJudgeSelection(null, 333L);
        assertTrue(EvalRunConfig.judgeEnabled(fromNull));
        assertEquals(Long.valueOf(333L), EvalRunConfig.judgeModelInstanceId(fromNull));
    }

    /**
     * case 级开关只跟随 run 级：历史导入数据里的 {@code scoring.openjudge.enabled=false}
     * 不得否决建跑时显式选择的打分模型（否则 Judge 列会全部「未启用」）。
     */
    @Test
    void caseOpenjudgeFollowsRunLevelEvenIfCaseSaysDisabled() {
        ObjectNode caseOff = json.createObjectNode();
        caseOff.putObject("openjudge").put("enabled", false);

        assertTrue(EvalRunConfig.caseOpenjudgeEnabled(caseOff, true),
                "run 级已选模型时，case 级 false 不再作为否决项");
        assertFalse(EvalRunConfig.caseOpenjudgeEnabled(caseOff, false), "run 级关闭则一律不发");
        assertFalse(EvalRunConfig.caseOpenjudgeEnabled(null, false));
    }

    /**
     * 构造 {"openjudge": {"enabled": ...}}；enabled 为 null 时只留空对象。
     * <p>注意 ObjectNode.putObject 返回的是**子节点**，直接链式调用会得到内层对象而不是根，
     * 因此这里显式取根节点返回。
     *
     * @param enabled 开关值，可空
     * @return 根节点
     */
    private ObjectNode configWithOpenjudge(JsonNode enabled) {
        ObjectNode root = json.createObjectNode();
        ObjectNode openjudge = root.putObject("openjudge");
        if (enabled != null) {
            openjudge.set("enabled", enabled);
        }
        return root;
    }
}

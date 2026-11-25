package com.agenthub.workflow.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSONLogic 子集求值单测。所属域：workflow 的 domain。
 * <p>硬钉：白名单算子可用；SpEL 风格字符串不生效；未知算子明确报错而不是静默 false；
 * 深度超限直接拒绝，防租户写超深表达式拖垮调度。
 */
class JsonLogicEvaluatorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("var 读取 input 嵌套字段")
    void varReadsInput() throws Exception {
        JsonNode data = context("{\"query\":{\"level\":\"P0\"}}");
        JsonNode rule = json.readTree("{\"==\":[{\"var\":\"input.query.level\"},\"P0\"]}");

        assertThat(JsonLogicEvaluator.test(rule, data)).isTrue();
    }

    @Test
    @DisplayName("and / or / 比较组合")
    void logicalOperators() throws Exception {
        JsonNode data = context("{\"amount\":120,\"vip\":true}");
        JsonNode rule = json.readTree("{\"and\":["
                + "{\">=\":[{\"var\":\"input.amount\"},100]},"
                + "{\"or\":[{\"var\":\"input.vip\"},{\"<\":[{\"var\":\"input.amount\"},50]}]}]}");

        assertThat(JsonLogicEvaluator.test(rule, data)).isTrue();
    }

    @Test
    @DisplayName("in 支持数组与字符串包含")
    void inOperator() throws Exception {
        JsonNode data = context("{\"status\":\"PAID\"}");

        assertThat(JsonLogicEvaluator.test(
                json.readTree("{\"in\":[{\"var\":\"input.status\"},[\"PAID\",\"SHIPPED\"]]}"), data)).isTrue();
        assertThat(JsonLogicEvaluator.test(
                json.readTree("{\"in\":[\"ZZ\",{\"var\":\"input.status\"}]}"), data)).isFalse();
    }

    @Test
    @DisplayName("missing 返回缺失路径")
    void missing() throws Exception {
        JsonNode data = context("{\"a\":1}");

        JsonNode result = JsonLogicEvaluator.eval(
                json.readTree("{\"missing\":[\"input.a\",\"input.b\"]}"), data, 0);

        assertThat(result.toString()).isEqualTo("[\"input.b\"]");
    }

    @Test
    @DisplayName("未知算子必须报错，不能当作 false 静默跳过")
    void unknownOperatorThrows() throws Exception {
        JsonNode data = context("{}");

        assertThatThrownBy(() -> JsonLogicEvaluator.test(
                json.readTree("{\"spel\":\"#input.a > 1\"}"), data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    @DisplayName("嵌套过深直接拒绝")
    void depthGuard() throws Exception {
        JsonNode data = context("{\"a\":1}");
        String rule = "{\"var\":\"input.a\"}";
        for (int i = 0; i < JsonLogicEvaluator.MAX_DEPTH + 2; i++) {
            rule = "{\"and\":[" + rule + "]}";
        }
        JsonNode deep = json.readTree(rule);

        assertThatThrownBy(() -> JsonLogicEvaluator.test(deep, data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("嵌套过深");
    }

    @Test
    @DisplayName("真值口径：0 / 空串 / 空数组为假")
    void truthyRules() throws Exception {
        JsonNode data = context("{\"zero\":0,\"empty\":\"\",\"list\":[]}");

        assertThat(JsonLogicEvaluator.test(json.readTree("{\"var\":\"input.zero\"}"), data)).isFalse();
        assertThat(JsonLogicEvaluator.test(json.readTree("{\"var\":\"input.empty\"}"), data)).isFalse();
        assertThat(JsonLogicEvaluator.test(json.readTree("{\"var\":\"input.list\"}"), data)).isFalse();
        assertThat(JsonLogicEvaluator.test(json.readTree("{\"!\":[{\"var\":\"input.zero\"}]}"), data)).isTrue();
    }

    private JsonNode context(String inputJson) throws Exception {
        ObjectNode ctx = JsonLogicEvaluator.context(
                json.readTree(inputJson), json.createObjectNode(), json.createObjectNode());
        return ctx;
    }
}

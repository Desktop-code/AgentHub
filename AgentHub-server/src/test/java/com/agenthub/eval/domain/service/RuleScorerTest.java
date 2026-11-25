package com.agenthub.eval.domain.service;

import com.agenthub.eval.domain.model.AssertionResult;
import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.RuleScoreResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuleScorer 纯域单测。所属域：eval。
 * <p>手造 {@link EvalArtifact}；禁真 trial / 真模型 / HTTP。
 */
class RuleScorerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emptyAssertionsPass() {
        RuleScoreResult result = RuleScorer.score(baseArtifact(), null, json);
        assertTrue(result.isRulePassed());
        assertEquals(0, result.getDetails().size());

        result = RuleScorer.score(baseArtifact(), json.createArrayNode(), json);
        assertTrue(result.isRulePassed());
    }

    @Test
    void unknownTypeFailsBlocking() {
        ArrayNode assertions = json.createArrayNode();
        assertions.add(obj("type", "not_a_real_type"));
        RuleScoreResult result = RuleScorer.score(baseArtifact(), assertions, json);
        assertFalse(result.isRulePassed());
        assertEquals(1, result.getBlockingFailed());
        assertTrue(result.getDetails().get(0).getMessage().contains("未知"));
    }

    @Test
    void warningFailureDoesNotFlipRulePassed() {
        ArrayNode assertions = json.createArrayNode();
        ObjectNode warn = obj("type", "final_contains");
        warn.put("blocking", false);
        warn.putObject("args").put("text", "绝对不存在的子串XYZ");
        assertions.add(warn);
        RuleScoreResult result = RuleScorer.score(baseArtifact(), assertions, json);
        assertTrue(result.isRulePassed());
        assertEquals(1, result.getWarningFailed());
        assertEquals(0, result.getBlockingFailed());
        assertFalse(result.getDetails().get(0).isOk());
    }

    @Test
    void nonArrayAssertionsFail() {
        RuleScoreResult result = RuleScorer.score(baseArtifact(), json.createObjectNode(), json);
        assertFalse(result.isRulePassed());
        assertTrue(result.getDetails().get(0).getMessage().contains("数组"));
    }

    @Test
    void nullArtifactDoesNotNpe() {
        ArrayNode assertions = json.createArrayNode();
        ObjectNode one = obj("type", "final_contains");
        one.putObject("args").put("text", "x");
        assertions.add(one);
        RuleScoreResult result = RuleScorer.score(null, assertions, json);
        assertFalse(result.isRulePassed());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("p0Cases")
    void p0AssertionTypes(String name, EvalArtifact artifact, JsonNode assertion, boolean expectOk) {
        ArrayNode assertions = json.createArrayNode();
        assertions.add(assertion);
        RuleScoreResult result = RuleScorer.score(artifact, assertions, json);
        AssertionResult detail = result.getDetails().get(0);
        assertEquals(expectOk, detail.isOk(), () -> name + " message=" + detail.getMessage());
        assertEquals(expectOk, result.isRulePassed());
    }

    static Stream<Arguments> p0Cases() throws Exception {
        ObjectMapper m = new ObjectMapper();
        EvalArtifact base = baseArtifactStatic();
        return Stream.of(
                Arguments.of("final_contains_ok", base,
                        assertion(m, "final_contains", a -> a.put("text", "你好")), true),
                Arguments.of("final_contains_fail", base,
                        assertion(m, "final_contains", a -> a.put("text", "不存在")), false),
                Arguments.of("final_not_contains_ok", base,
                        assertion(m, "final_not_contains", a -> a.put("text", "机密")), true),
                Arguments.of("final_not_contains_fail", base,
                        assertion(m, "final_not_contains", a -> a.put("text", "世界")), false),
                Arguments.of("final_regex_ok", base,
                        assertion(m, "final_regex", a -> a.put("pattern", "你好.+世界")), true),
                Arguments.of("final_regex_bad_pattern", base,
                        assertion(m, "final_regex", a -> a.put("pattern", "[")), false),
                Arguments.of("json_path_equals_ok", jsonAnswerArtifact(m),
                        assertion(m, "json_path_equals", a -> {
                            a.put("path", "$.ok");
                            a.put("equals", true);
                        }), true),
                Arguments.of("json_path_equals_not_json", base,
                        assertion(m, "json_path_equals", a -> {
                            a.put("path", "$.ok");
                            a.put("equals", true);
                        }), false),
                Arguments.of("tool_called_ok", toolArtifact(),
                        assertion(m, "tool_called", a -> a.put("name", "search")), true),
                Arguments.of("tool_called_fail", toolArtifact(),
                        assertion(m, "tool_called", a -> a.put("name", "missing")), false),
                Arguments.of("tool_not_called_ok", toolArtifact(),
                        assertion(m, "tool_not_called", a -> a.put("name", "hack")), true),
                Arguments.of("tool_not_called_fail", toolArtifact(),
                        assertion(m, "tool_not_called", a -> a.put("name", "search")), false),
                Arguments.of("tool_sequence_ok", toolArtifact(),
                        assertion(m, "tool_sequence", a -> a.putArray("names").add("search").add("calc")), true),
                Arguments.of("tool_sequence_fail", toolArtifact(),
                        assertion(m, "tool_sequence", a -> a.putArray("names").add("calc").add("search")), false),
                Arguments.of("max_tool_calls_ok", toolArtifact(),
                        assertion(m, "max_tool_calls", a -> a.put("max", 5)), true),
                Arguments.of("max_tool_calls_fail", toolArtifact(),
                        assertion(m, "max_tool_calls", a -> a.put("max", 1)), false),
                Arguments.of("finish_reason_in_ok", base,
                        assertion(m, "finish_reason_in", a -> a.putArray("values").add("stop")), true),
                Arguments.of("finish_reason_in_fail", base,
                        assertion(m, "finish_reason_in", a -> a.putArray("values").add("length")), false),
                Arguments.of("status_equals_ok", base,
                        assertion(m, "status_equals", a -> a.put("status", "SUCCEEDED")), true),
                Arguments.of("status_equals_fail", base,
                        assertion(m, "status_equals", a -> a.put("status", "FAILED")), false),
                Arguments.of("max_latency_ms_ok", base,
                        assertion(m, "max_latency_ms", a -> a.put("max", 1000)), true),
                Arguments.of("max_latency_ms_fail", base,
                        assertion(m, "max_latency_ms", a -> a.put("max", 5)), false),
                Arguments.of("prompt_chars_max_ok", promptArtifact(),
                        assertion(m, "prompt_chars_max", a -> a.put("max", 100)), true),
                Arguments.of("prompt_chars_max_missing_breakdown", base,
                        assertion(m, "prompt_chars_max", a -> a.put("max", 100)), false),
                Arguments.of("cache_hit_equals_ok", base,
                        assertion(m, "cache_hit_equals", a -> a.put("expected", false)), true),
                Arguments.of("cache_hit_equals_fail", base,
                        assertion(m, "cache_hit_equals", a -> a.put("expected", true)), false)
        );
    }

    private ObjectNode obj(String k, String v) {
        ObjectNode node = json.createObjectNode();
        node.put(k, v);
        return node;
    }

    private EvalArtifact baseArtifact() {
        return baseArtifactStatic();
    }

    private static EvalArtifact baseArtifactStatic() {
        EvalArtifact art = new EvalArtifact();
        art.setStatus("SUCCESS");
        art.setFinalAnswer("你好，世界");
        art.setFinishReason("stop");
        art.setLatencyMs(20);
        art.setCacheHit(false);
        return art;
    }

    private static EvalArtifact jsonAnswerArtifact(ObjectMapper m) throws Exception {
        EvalArtifact art = baseArtifactStatic();
        art.setFinalAnswer(m.writeValueAsString(Map.of("ok", true, "n", 1)));
        return art;
    }

    private static EvalArtifact toolArtifact() {
        EvalArtifact art = baseArtifactStatic();
        EvalArtifact.ToolCallSummary a = new EvalArtifact.ToolCallSummary();
        a.setName("search");
        a.setSuccess(true);
        EvalArtifact.ToolCallSummary b = new EvalArtifact.ToolCallSummary();
        b.setName("calc");
        b.setSuccess(true);
        art.setToolCalls(List.of(a, b));
        return art;
    }

    private static EvalArtifact promptArtifact() {
        EvalArtifact art = baseArtifactStatic();
        art.setPromptBreakdown(Map.of("user_total", 40, "recent", 10));
        return art;
    }

    private static JsonNode assertion(ObjectMapper m, String type,
                                      java.util.function.Consumer<ObjectNode> argsFiller) {
        ObjectNode node = m.createObjectNode();
        node.put("type", type);
        ObjectNode args = node.putObject("args");
        argsFiller.accept(args);
        return node;
    }
}

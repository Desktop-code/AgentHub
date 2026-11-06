package com.agenthub.eval.domain.service;

import com.agenthub.common.text.TextClip;
import com.agenthub.eval.domain.model.AssertionResult;
import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.RuleScoreResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 规则硬门禁打分器。所属域：eval 的 domain。
 * <p>纯确定性、零 LLM、无 HTTP/Trial：只读 {@link EvalArtifact} + assertions_json。
 * <p>P0 类型对齐定稿 §3.4（不含 span_exists / llm_judge_inline）。
 * <p>口径：
 * <ul>
 *   <li>空断言 → rule_passed=true；</li>
 *   <li>blocking（默认 true；severity=warning 或 blocking=false 为 warning）失败 → 拉低 rule_passed；</li>
 *   <li>warning 失败只进明细；</li>
 *   <li>未知 type / 缺必填 args / 内部异常 → 该条 ok=false，不拖垮 JVM。</li>
 * </ul>
 * 线程：无状态，可并发调用。
 */
public final class RuleScorer {

    private RuleScorer() {
    }

    /**
     * 对产物跑断言列表。
     *
     * @param artifact   试跑产物，可空（按空串/空列表评估，勿 NPE）
     * @param assertions assertions_json 节点：须为数组；null/缺省当 []；非数组 → 一条 blocking 失败
     * @param json       解析器（json_path_equals 用）
     * @return 聚合结果
     */
    public static RuleScoreResult score(EvalArtifact artifact, JsonNode assertions, ObjectMapper json) {
        RuleScoreResult result = new RuleScoreResult();
        List<AssertionResult> details = new ArrayList<>();
        if (assertions == null || assertions.isNull() || assertions.isMissingNode()) {
            result.setRulePassed(true);
            result.setDetails(details);
            return result;
        }
        if (!assertions.isArray()) {
            details.add(AssertionResult.of("?", true, false, "assertions 必须是数组"));
            result.setDetails(details);
            result.setBlockingTotal(1);
            result.setBlockingFailed(1);
            result.setRulePassed(false);
            return result;
        }

        EvalArtifact art = artifact == null ? new EvalArtifact() : artifact;
        int blockingTotal = 0;
        int blockingFailed = 0;
        int warningFailed = 0;

        for (JsonNode item : assertions) {
            AssertionResult one = evalOne(art, item, json);
            details.add(one);
            if (one.isBlocking()) {
                blockingTotal++;
                if (!one.isOk()) {
                    blockingFailed++;
                }
            } else if (!one.isOk()) {
                warningFailed++;
            }
        }

        result.setDetails(details);
        result.setBlockingTotal(blockingTotal);
        result.setBlockingFailed(blockingFailed);
        result.setWarningFailed(warningFailed);
        result.setRulePassed(blockingFailed == 0);
        return result;
    }

    private static AssertionResult evalOne(EvalArtifact art, JsonNode item, ObjectMapper json) {
        if (item == null || !item.isObject()) {
            return AssertionResult.of("?", true, false, "断言项必须是对象");
        }
        JsonNode typeNode = item.get("type");
        if (typeNode == null || !typeNode.isTextual() || typeNode.asText().isBlank()) {
            return AssertionResult.of("?", resolveBlocking(item), false, "断言缺少 type");
        }
        String type = typeNode.asText().trim().toLowerCase(Locale.ROOT);
        boolean blocking = resolveBlocking(item);
        JsonNode args = item.get("args");
        if (args == null || args.isNull()) {
            args = item; // 容忍把参数写在顶层的宽松形态
        }
        try {
            return switch (type) {
                case "final_contains" -> finalContains(art, args, blocking, true);
                case "final_not_contains" -> finalContains(art, args, blocking, false);
                case "final_regex" -> finalRegex(art, args, blocking);
                case "json_path_equals" -> jsonPathEquals(art, args, blocking, json);
                case "tool_called" -> toolCalled(art, args, blocking, true);
                case "tool_not_called" -> toolCalled(art, args, blocking, false);
                case "tool_sequence" -> toolSequence(art, args, blocking);
                case "max_tool_calls" -> maxToolCalls(art, args, blocking);
                case "finish_reason_in" -> finishReasonIn(art, args, blocking);
                case "status_equals" -> statusEquals(art, args, blocking);
                case "max_latency_ms" -> maxLatency(art, args, blocking);
                case "prompt_chars_max" -> promptCharsMax(art, args, blocking);
                case "cache_hit_equals" -> cacheHitEquals(art, args, blocking);
                case "llm_judge_inline", "span_exists" -> AssertionResult.of(type, blocking, false,
                        "断言类型 " + type + " 不在 RuleScorer P0 范围");
                default -> AssertionResult.of(type, blocking, false, "未知断言类型：" + type);
            };
        } catch (RuntimeException ex) {
            return AssertionResult.of(type, blocking, false,
                    "断言执行异常：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    /**
     * blocking 缺省 true；blocking=false 或 severity=warning → warning。
     */
    private static boolean resolveBlocking(JsonNode item) {
        JsonNode blocking = item.get("blocking");
        if (blocking != null) {
            if (blocking.isBoolean()) {
                return blocking.asBoolean();
            }
            if (blocking.isInt()) {
                return blocking.asInt() != 0;
            }
        }
        JsonNode severity = item.get("severity");
        if (severity != null && severity.isTextual()) {
            String raw = severity.asText().trim().toLowerCase(Locale.ROOT);
            if ("warning".equals(raw) || "warn".equals(raw)) {
                return false;
            }
        }
        return true;
    }

    private static AssertionResult finalContains(EvalArtifact art, JsonNode args, boolean blocking, boolean expectContain) {
        String text = textArg(args, "text", "value");
        if (text == null) {
            return AssertionResult.of(expectContain ? "final_contains" : "final_not_contains",
                    blocking, false, "缺少 args.text");
        }
        boolean ignoreCase = boolArg(args, "ignoreCase", false) || boolArg(args, "ignore_case", false);
        String answer = nullToEmpty(art.getFinalAnswer());
        String hay = ignoreCase ? answer.toLowerCase(Locale.ROOT) : answer;
        String needle = ignoreCase ? text.toLowerCase(Locale.ROOT) : text;
        boolean contains = hay.contains(needle);
        boolean ok = expectContain == contains;
        String type = expectContain ? "final_contains" : "final_not_contains";
        return AssertionResult.of(type, blocking, ok,
                ok ? (expectContain ? "终答包含期望子串" : "终答未包含禁止子串")
                        : (expectContain ? "终答未包含期望子串" : "终答包含了禁止子串"),
                text, TextClip.raw(answer, 120));
    }

    private static AssertionResult finalRegex(EvalArtifact art, JsonNode args, boolean blocking) {
        String pattern = textArg(args, "pattern", "regex");
        if (pattern == null) {
            return AssertionResult.of("final_regex", blocking, false, "缺少 args.pattern");
        }
        try {
            Pattern compiled = Pattern.compile(pattern);
            String answer = nullToEmpty(art.getFinalAnswer());
            boolean ok = compiled.matcher(answer).find();
            return AssertionResult.of("final_regex", blocking, ok,
                    ok ? "终答匹配正则" : "终答不匹配正则", pattern, TextClip.raw(answer, 120));
        } catch (PatternSyntaxException ex) {
            return AssertionResult.of("final_regex", blocking, false, "非法正则：" + ex.getDescription(),
                    pattern, null);
        }
    }

    private static AssertionResult jsonPathEquals(EvalArtifact art, JsonNode args, boolean blocking, ObjectMapper json) {
        String path = textArg(args, "path", "json_path");
        if (path == null) {
            return AssertionResult.of("json_path_equals", blocking, false, "缺少 args.path");
        }
        JsonNode expected = args.get("equals");
        if (expected == null) {
            expected = args.get("expected");
        }
        if (expected == null) {
            expected = args.get("value");
        }
        if (expected == null) {
            return AssertionResult.of("json_path_equals", blocking, false, "缺少 args.equals/expected");
        }
        String answer = nullToEmpty(art.getFinalAnswer()).trim();
        JsonNode root;
        try {
            root = json.readTree(answer);
        } catch (Exception ex) {
            return AssertionResult.of("json_path_equals", blocking, false, "终答不是合法 JSON",
                    path, TextClip.raw(answer, 80));
        }
        JsonNode actual = walkPath(root, path);
        if (actual == null || actual.isMissingNode()) {
            return AssertionResult.of("json_path_equals", blocking, false, "路径不存在：" + path,
                    String.valueOf(expected), null);
        }
        boolean ok = jsonNodesEqual(actual, expected);
        return AssertionResult.of("json_path_equals", blocking, ok,
                ok ? "JSON 路径值相等" : "JSON 路径值不等",
                String.valueOf(expected), String.valueOf(actual));
    }

    private static AssertionResult toolCalled(EvalArtifact art, JsonNode args, boolean blocking, boolean expectCalled) {
        String name = textArg(args, "name", "tool");
        if (name == null) {
            return AssertionResult.of(expectCalled ? "tool_called" : "tool_not_called",
                    blocking, false, "缺少 args.name");
        }
        boolean requireSuccess = boolArg(args, "success", false);
        List<EvalArtifact.ToolCallSummary> tools = art.getToolCalls() == null ? List.of() : art.getToolCalls();
        boolean found = false;
        for (EvalArtifact.ToolCallSummary one : tools) {
            if (one == null || one.getName() == null) {
                continue;
            }
            if (!name.equals(one.getName())) {
                continue;
            }
            if (requireSuccess && !Boolean.TRUE.equals(one.getSuccess())) {
                continue;
            }
            found = true;
            break;
        }
        boolean ok = expectCalled == found;
        String type = expectCalled ? "tool_called" : "tool_not_called";
        return AssertionResult.of(type, blocking, ok,
                ok ? (expectCalled ? "已调用工具" : "未调用禁止工具")
                        : (expectCalled ? "未调用期望工具" : "调用了禁止工具"),
                name, "calls=" + tools.size());
    }

    private static AssertionResult toolSequence(EvalArtifact art, JsonNode args, boolean blocking) {
        List<String> expected = stringListArg(args, "names", "sequence");
        if (expected.isEmpty()) {
            return AssertionResult.of("tool_sequence", blocking, false, "缺少 args.names");
        }
        List<String> actual = new ArrayList<>();
        if (art.getToolCalls() != null) {
            for (EvalArtifact.ToolCallSummary one : art.getToolCalls()) {
                if (one != null && one.getName() != null) {
                    actual.add(one.getName());
                }
            }
        }
        boolean ok = isSubsequence(actual, expected);
        return AssertionResult.of("tool_sequence", blocking, ok,
                ok ? "工具序列子序列匹配" : "工具序列不匹配",
                expected.toString(), actual.toString());
    }

    private static AssertionResult maxToolCalls(EvalArtifact art, JsonNode args, boolean blocking) {
        Integer max = intArg(args, "max", "limit");
        if (max == null) {
            return AssertionResult.of("max_tool_calls", blocking, false, "缺少 args.max");
        }
        int actual = art.getToolCalls() == null ? 0 : art.getToolCalls().size();
        boolean ok = actual <= max;
        return AssertionResult.of("max_tool_calls", blocking, ok,
                ok ? "工具调用次数未超限" : "工具调用次数超限",
                String.valueOf(max), String.valueOf(actual));
    }

    private static AssertionResult finishReasonIn(EvalArtifact art, JsonNode args, boolean blocking) {
        List<String> values = stringListArg(args, "values", "value");
        if (values.isEmpty()) {
            String single = textArg(args, "value", "reason");
            if (single != null) {
                values = List.of(single);
            }
        }
        if (values.isEmpty()) {
            return AssertionResult.of("finish_reason_in", blocking, false, "缺少 args.values");
        }
        String actual = art.getFinishReason() == null ? "" : art.getFinishReason().trim();
        boolean ok = values.stream().anyMatch(one -> one.equalsIgnoreCase(actual));
        return AssertionResult.of("finish_reason_in", blocking, ok,
                ok ? "finish_reason 命中集合" : "finish_reason 不在集合",
                values.toString(), actual);
    }

    private static AssertionResult statusEquals(EvalArtifact art, JsonNode args, boolean blocking) {
        String expected = textArg(args, "status", "expected");
        if (expected == null) {
            return AssertionResult.of("status_equals", blocking, false, "缺少 args.status");
        }
        String actual = art.getStatus() == null ? "" : art.getStatus().trim();
        boolean ok = statusMatches(expected, actual);
        return AssertionResult.of("status_equals", blocking, ok,
                ok ? "status 匹配" : "status 不匹配", expected, actual);
    }

    private static AssertionResult maxLatency(EvalArtifact art, JsonNode args, boolean blocking) {
        Integer max = intArg(args, "max", "max_ms");
        if (max == null) {
            max = intArg(args, "maxLatencyMs", "latency_ms");
        }
        if (max == null) {
            return AssertionResult.of("max_latency_ms", blocking, false, "缺少 args.max");
        }
        int actual = art.getLatencyMs() == null ? Integer.MAX_VALUE : art.getLatencyMs();
        boolean ok = art.getLatencyMs() != null && actual <= max;
        return AssertionResult.of("max_latency_ms", blocking, ok,
                ok ? "耗时未超限" : "耗时超限或缺失",
                String.valueOf(max), art.getLatencyMs() == null ? "null" : String.valueOf(actual));
    }

    private static AssertionResult promptCharsMax(EvalArtifact art, JsonNode args, boolean blocking) {
        Integer max = intArg(args, "max", "limit");
        if (max == null) {
            return AssertionResult.of("prompt_chars_max", blocking, false, "缺少 args.max");
        }
        String path = textArg(args, "path", "field");
        Map<String, Integer> breakdown = art.getPromptBreakdown();
        if (breakdown == null || breakdown.isEmpty()) {
            // 写死：缺 breakdown 且断言存在 → 该条 fail（视为超限/不可验证）
            return AssertionResult.of("prompt_chars_max", blocking, false,
                    "缺少 prompt_breakdown，无法校验", String.valueOf(max), "null");
        }
        int actual;
        if (path == null || path.isBlank() || "user_total".equals(path) || "total".equals(path)) {
            if (breakdown.containsKey("user_total")) {
                actual = breakdown.get("user_total") == null ? 0 : breakdown.get("user_total");
            } else if (breakdown.containsKey("total")) {
                actual = breakdown.get("total") == null ? 0 : breakdown.get("total");
            } else {
                actual = breakdown.values().stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
            }
        } else {
            Integer value = breakdown.get(path);
            if (value == null) {
                return AssertionResult.of("prompt_chars_max", blocking, false,
                        "prompt_breakdown 无路径：" + path, String.valueOf(max), "null");
            }
            actual = value;
        }
        boolean ok = actual <= max;
        return AssertionResult.of("prompt_chars_max", blocking, ok,
                ok ? "提示词字符未超限" : "提示词字符超限",
                String.valueOf(max), String.valueOf(actual));
    }

    private static AssertionResult cacheHitEquals(EvalArtifact art, JsonNode args, boolean blocking) {
        Boolean expected = boolArgNullable(args, "expected");
        if (expected == null) {
            expected = boolArgNullable(args, "value");
        }
        if (expected == null) {
            expected = boolArgNullable(args, "cache_hit");
        }
        if (expected == null) {
            return AssertionResult.of("cache_hit_equals", blocking, false, "缺少 args.expected");
        }
        boolean actual = Boolean.TRUE.equals(art.getCacheHit());
        boolean ok = actual == expected;
        return AssertionResult.of("cache_hit_equals", blocking, ok,
                ok ? "cache_hit 匹配" : "cache_hit 不匹配",
                String.valueOf(expected), String.valueOf(actual));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * SUCCESS ↔ SUCCEEDED 互认（trial 门面写 SUCCESS，样例断言常写 SUCCEEDED）。
     */
    static boolean statusMatches(String expected, String actual) {
        if (expected == null) {
            return false;
        }
        if (expected.equalsIgnoreCase(actual)) {
            return true;
        }
        String e = expected.trim().toUpperCase(Locale.ROOT);
        String a = actual == null ? "" : actual.trim().toUpperCase(Locale.ROOT);
        if (("SUCCESS".equals(e) || "SUCCEEDED".equals(e))
                && ("SUCCESS".equals(a) || "SUCCEEDED".equals(a))) {
            return true;
        }
        return false;
    }

    static boolean isSubsequence(List<String> actual, List<String> expected) {
        int i = 0;
        for (String one : actual) {
            if (i < expected.size() && Objects.equals(one, expected.get(i))) {
                i++;
            }
        }
        return i == expected.size();
    }

    /**
     * 极简路径：支持 {@code $.a.b} / {@code a.b} / {@code a[0].b}；无 Jayway 依赖。
     */
    static JsonNode walkPath(JsonNode root, String rawPath) {
        if (root == null || rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String path = rawPath.trim();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        } else if (path.startsWith("$")) {
            path = path.substring(1);
            if (path.startsWith(".")) {
                path = path.substring(1);
            }
        }
        if (path.isEmpty()) {
            return root;
        }
        JsonNode cur = root;
        for (String segment : path.split("\\.")) {
            if (cur == null || cur.isMissingNode() || cur.isNull()) {
                return null;
            }
            String name = segment;
            Integer index = null;
            int bracket = segment.indexOf('[');
            if (bracket >= 0 && segment.endsWith("]")) {
                name = segment.substring(0, bracket);
                try {
                    index = Integer.parseInt(segment.substring(bracket + 1, segment.length() - 1));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            if (!name.isEmpty()) {
                cur = cur.get(name);
            }
            if (index != null) {
                if (cur == null || !cur.isArray() || index < 0 || index >= cur.size()) {
                    return null;
                }
                cur = cur.get(index);
            }
        }
        return cur;
    }

    private static boolean jsonNodesEqual(JsonNode actual, JsonNode expected) {
        if (actual == null || expected == null) {
            return false;
        }
        // 数字宽松：1 vs 1.0
        if (actual.isNumber() && expected.isNumber()) {
            return actual.decimalValue().compareTo(expected.decimalValue()) == 0;
        }
        if (actual.isValueNode() && expected.isValueNode()) {
            return Objects.equals(actual.asText(), expected.asText())
                    || actual.equals(expected);
        }
        return actual.equals(expected);
    }

    private static String textArg(JsonNode args, String primary, String alias) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(primary);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            node = args.get(alias);
        }
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    private static Integer intArg(JsonNode args, String primary, String alias) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(primary);
        if (node == null || !node.isNumber()) {
            node = args.get(alias);
        }
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }

    private static boolean boolArg(JsonNode args, String name, boolean fallback) {
        Boolean value = boolArgNullable(args, name);
        return value == null ? fallback : value;
    }

    private static Boolean boolArgNullable(JsonNode args, String name) {
        if (args == null) {
            return null;
        }
        JsonNode node = args.get(name);
        if (node == null) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt()) {
            return node.asInt() != 0;
        }
        if (node.isTextual()) {
            String raw = node.asText().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(raw) || "1".equals(raw)) {
                return true;
            }
            if ("false".equals(raw) || "0".equals(raw)) {
                return false;
            }
        }
        return null;
    }

    private static List<String> stringListArg(JsonNode args, String primary, String alias) {
        List<String> list = new ArrayList<>();
        if (args == null) {
            return list;
        }
        JsonNode node = args.get(primary);
        if (node == null) {
            node = args.get(alias);
        }
        if (node == null) {
            return list;
        }
        if (node.isArray()) {
            for (JsonNode one : node) {
                if (one != null && one.isTextual() && !one.asText().isBlank()) {
                    list.add(one.asText().trim());
                }
            }
        } else if (node.isTextual() && !node.asText().isBlank()) {
            list.add(node.asText().trim());
        }
        return list;
    }

    private static String nullToEmpty(String raw) {
        return raw == null ? "" : raw;
    }

}

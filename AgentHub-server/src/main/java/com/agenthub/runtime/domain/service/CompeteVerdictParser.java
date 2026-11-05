package com.agenthub.runtime.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 解析竞争选优（COMPETE）评选模型的输出。所属域：runtime。
 *
 * <p>契约：模型只回一个 JSON 对象 {"winner":"route_key","reason":"短理由"}；为兼容真实模型的随机性，额外容忍：
 * <ul>
 *   <li>包 markdown 代码块、带前后解释文字；</li>
 *   <li>字段名写成 winner / winner_key / route_key / pick；理由写成 reason / why / rationale；</li>
 *   <li>不返回 JSON、只回复一个合法 route_key（旧式「只吐一个 key」）；</li>
 *   <li>route_key 大小写不一致——比对不敏感，回写用候选原值。</li>
 * </ul>
 *
 * <p>约束：纯函数，不查库、不调用模型、不抛业务异常；解析不出时返回空，由调用方降级 FIRST_SUCCESS。
 */
public final class CompeteVerdictParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 理由最长字符数：只进时间线一行，超长会挤掉真正的结论 */
    private static final int REASON_LIMIT = 120;

    private CompeteVerdictParser() {
    }

    /**
     * 一次选优结论。
     *
     * @param winnerKey 与候选 route_key 对齐的赢家键（候选原值）
     * @param reason    模型给出的短理由；没有时为空串
     */
    public record Verdict(String winnerKey, String reason) {
    }

    /**
     * 从模型原文抽出赢家。
     *
     * @param reply       模型原文，可空
     * @param allowedKeys 本轮参与竞争的候选 route_key（大小写不敏感比对）
     * @return 有效结论；模型没给出合法赢家时为空
     */
    public static Optional<Verdict> parse(String reply, List<String> allowedKeys) {
        if (reply == null || reply.isBlank() || allowedKeys == null || allowedKeys.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> keyByLower = new LinkedHashMap<>();
        for (String key : allowedKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            String trimmed = key.trim();
            keyByLower.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
        if (keyByLower.isEmpty()) {
            return Optional.empty();
        }
        // 只要回复里出现过结构化 JSON，就只按 JSON 解析：不再退回「全文包含 key」，
        // 否则 "reason" 里的 a 这类字符会被误判成赢家
        Optional<JsonNode> root = extractJsonNode(reply);
        if (root.isPresent()) {
            return tryParseJson(root.get(), keyByLower);
        }
        return tryParsePlainKey(reply, keyByLower);
    }

    /**
     * 序列化为 ROUTE.route_decision / 观测用 JSON，便于泳道与排查对齐。
     *
     * @param verdict 选优结论；可空
     * @return JSON 字符串；空结论返回空对象串
     */
    public static String toJson(Verdict verdict) {
        Map<String, String> row = new LinkedHashMap<>();
        if (verdict != null) {
            row.put("winner", verdict.winnerKey() == null ? "" : verdict.winnerKey());
            row.put("reason", verdict.reason() == null ? "" : verdict.reason());
        }
        try {
            return JSON.writeValueAsString(row);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static Optional<Verdict> tryParseJson(JsonNode root, Map<String, String> keyByLower) {
        JsonNode node = root;
        // 容忍模型多包一层数组：取第一个对象
        if (node.isArray()) {
            if (node.isEmpty() || !node.get(0).isObject()) {
                return Optional.empty();
            }
            node = node.get(0);
        }
        if (!node.isObject()) {
            return Optional.empty();
        }
        String rawKey = firstText(node, "winner", "winner_key", "winnerKey", "route_key", "routeKey", "pick");
        if (rawKey.isEmpty()) {
            return Optional.empty();
        }
        String canonical = keyByLower.get(rawKey.toLowerCase(Locale.ROOT));
        if (canonical == null) {
            return Optional.empty();
        }
        return Optional.of(new Verdict(canonical, briefReason(firstText(node, "reason", "why", "rationale"))));
    }

    /**
     * 兜底：模型只回复一个 route_key（常见于「不要解释」的提示词）。
     *
     * <p>按「最长命中」取一个，避免 a / aa 这类前缀键互相误判。
     */
    private static Optional<Verdict> tryParsePlainKey(String reply, Map<String, String> keyByLower) {
        String text = reply.toLowerCase(Locale.ROOT);
        String best = null;
        int longest = -1;
        for (Map.Entry<String, String> entry : keyByLower.entrySet()) {
            if (text.contains(entry.getKey()) && entry.getKey().length() > longest) {
                best = entry.getValue();
                longest = entry.getKey().length();
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Verdict(best, ""));
    }

    private static Optional<JsonNode> extractJsonNode(String reply) {
        String text = reply.trim();
        // 容忍模型包一层 markdown 代码块
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        int objStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        int start;
        char endChar;
        if (objStart >= 0 && (arrayStart < 0 || objStart <= arrayStart)) {
            start = objStart;
            endChar = '}';
        } else if (arrayStart >= 0) {
            start = arrayStart;
            endChar = ']';
        } else {
            return Optional.empty();
        }
        int end = text.lastIndexOf(endChar);
        if (end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(text.substring(start, end + 1)));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child == null || child.isNull()) {
                continue;
            }
            String text = child.asText();
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    /**
     * 理由清洗：去换行、压空白、截断。理由只是锦上添花，不能让它撑爆时间线一行。
     */
    private static String briefReason(String raw) {
        String text = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        if (text.length() <= REASON_LIMIT) {
            return text;
        }
        return text.substring(0, REASON_LIMIT) + "…";
    }
}


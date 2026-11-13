package com.agenthub.runtime.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 解析 Router 判路由模型输出。所属域：runtime。
 * 契约：有序列表 [{"route_key":"...","sub_query":"...","reason":"..."}, ...]；
 * 兼容单对象、旧「只吐一个 key」，以及没有 reason 的历史输出。
 *
 * <p>reason 是判路由模型随同一次判定产出的自然语言依据，不是系统标签：
 * 命中时由 {@code RouterTrialExecutor} 原样带进 {@code RoutedIntent.reason}，
 * 工作台智能网关步骤优先展示它；缺失时前端再降级为「已分发给 X」。
 */
public final class RouteDecisionParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 理由硬顶：一句中文依据，超出按提示词预算截断，避免把整段推理塞进时间线 */
    private static final int REASON_MAX = 200;

    private RouteDecisionParser() {
    }

    /**
     * 一条有效路由意图。
     *
     * @param routeKey  与候选 route_key 对齐
     * @param subQuery  切给该子的子问题
     * @param reason    判路由模型给出的一句自然语言依据；模型未给时为空串
     */
    public record Item(String routeKey, String subQuery, String reason) {

        /**
         * 兼容历史调用：没有理由时按空串处理，不伪造「父模型」等系统标签。
         *
         * @param routeKey 候选键
         * @param subQuery 子问题
         */
        public Item(String routeKey, String subQuery) {
            this(routeKey, subQuery, "");
        }
    }

    /**
     * 从模型原文抽出有序意图列表；无效 key 丢弃；同 key 保留首次；截到 maxItems。
     * 不负责「全无效 → 兜底」，由调用方处理。
     *
     * @param reply      模型原文
     * @param allowedKeys 候选 route_key（大小写不敏感比对，回写用候选原值）
     * @param maxItems   硬顶
     * @param fallbackSubQuery 缺 sub_query 时用整句
     * @return 有效项；可能为空
     */
    public static List<Item> parse(String reply, List<String> allowedKeys, int maxItems, String fallbackSubQuery) {
        Map<String, String> keyByLower = new LinkedHashMap<>();
        if (allowedKeys != null) {
            for (String key : allowedKeys) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                String trimmed = key.trim();
                keyByLower.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        int cap = Math.max(1, maxItems);
        String fallback = fallbackSubQuery == null ? "" : fallbackSubQuery.trim();
        List<Item> fromJson = tryParseJson(reply, keyByLower, cap, fallback);
        if (!fromJson.isEmpty()) {
            return fromJson;
        }
        return tryParseLegacyKey(reply, keyByLower, fallback);
    }

    /**
     * 序列化为 ROUTE.route_decision / 观测用 JSON 列表。
     *
     * @param items 有效意图
     * @return JSON 数组字符串
     */
    public static String toDecisionJson(List<Item> items) {
        try {
            List<Map<String, String>> rows = new ArrayList<>();
            if (items != null) {
                for (Item item : items) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("route_key", item.routeKey());
                    row.put("sub_query", item.subQuery());
                    // 理由随判定一次产出：保留在决策 JSON 里，前端 / 运行页需要时可读，不另打一轮模型
                    if (item.reason() != null && !item.reason().isBlank()) {
                        row.put("reason", item.reason());
                    }
                    rows.add(row);
                }
            }
            return JSON.writeValueAsString(rows);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static List<Item> tryParseJson(String reply, Map<String, String> keyByLower, int cap, String fallback) {
        Optional<JsonNode> root = extractJsonNode(reply);
        if (root.isEmpty()) {
            return List.of();
        }
        JsonNode node = root.get();
        List<JsonNode> elements = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(elements::add);
        } else if (node.isObject()) {
            elements.add(node);
        } else {
            return List.of();
        }
        LinkedHashMap<String, Item> unique = new LinkedHashMap<>();
        for (JsonNode el : elements) {
            if (unique.size() >= cap) {
                break;
            }
            if (el == null || !el.isObject()) {
                continue;
            }
            String rawKey = textOf(el, "route_key");
            if (rawKey.isEmpty()) {
                rawKey = textOf(el, "routeKey");
            }
            if (rawKey.isEmpty()) {
                continue;
            }
            String canonical = keyByLower.get(rawKey.toLowerCase(Locale.ROOT));
            if (canonical == null) {
                continue;
            }
            if (unique.containsKey(canonical.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String sub = textOf(el, "sub_query");
            if (sub.isEmpty()) {
                sub = textOf(el, "subQuery");
            }
            if (sub.isEmpty()) {
                sub = fallback;
            }
            String reason = reasonOf(el);
            unique.put(canonical.toLowerCase(Locale.ROOT), new Item(canonical, sub, reason));
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 取一条判定里的自然语言理由。
     *
     * <p>兼容模型常见的几种字段名；只做展示口径的清洗：
     * 压成一行、去掉首尾引号、过滤系统标签（父模型 / 子模型），超长截断。
     * 不做「没有理由就编一个」——空就是空，前端会降级成中性句。
     *
     * @param node 单个判定对象
     * @return 可展示的一句理由；没有或只是系统标签时为空串
     */
    private static String reasonOf(JsonNode node) {
        String raw = textOf(node, "reason");
        if (raw.isEmpty()) {
            raw = textOf(node, "basis");
        }
        if (raw.isEmpty()) {
            raw = textOf(node, "why");
        }
        if (raw.isEmpty()) {
            raw = textOf(node, "rationale");
        }
        if (raw.isEmpty()) {
            return "";
        }
        // 多行 / 连续空白压成一行：时间线只接一句，不要把模型的分点推理整段搬进来
        String text = raw.replaceAll("\\s+", " ").trim();
        // 系统标签不是「为什么选它」：命中即丢，避免旧模板 / 旧数据回流成假理由
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("父模型") || lower.contains("子模型")) {
            return "";
        }
        if (text.length() > REASON_MAX) {
            text = text.substring(0, REASON_MAX).trim() + "…";
        }
        return text;
    }

    private static List<Item> tryParseLegacyKey(String reply, Map<String, String> keyByLower, String fallback) {
        if (reply == null || reply.isBlank() || keyByLower.isEmpty()) {
            return List.of();
        }
        String text = reply.toLowerCase(Locale.ROOT);
        String bestKey = null;
        int longest = -1;
        for (Map.Entry<String, String> entry : keyByLower.entrySet()) {
            String lower = entry.getKey();
            if (text.contains(lower) && lower.length() > longest) {
                bestKey = entry.getValue();
                longest = lower.length();
            }
        }
        if (bestKey == null) {
            return List.of();
        }
        return List.of(new Item(bestKey, fallback));
    }

    private static Optional<JsonNode> extractJsonNode(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        String text = reply.trim();
        // 容忍模型包一层 markdown 代码块
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        int arrayStart = text.indexOf('[');
        int objStart = text.indexOf('{');
        int start;
        char endChar;
        if (arrayStart >= 0 && (objStart < 0 || arrayStart < objStart)) {
            start = arrayStart;
            endChar = ']';
        } else if (objStart >= 0) {
            start = objStart;
            endChar = '}';
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

    private static String textOf(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return "";
        }
        String text = child.asText();
        return text == null ? "" : text.trim();
    }
}

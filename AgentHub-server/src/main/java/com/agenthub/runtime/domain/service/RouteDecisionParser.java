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
 * 契约：有序列表 [{"route_key":"...","sub_query":"..."}, ...]；兼容单对象与旧「只吐一个 key」。
 */
public final class RouteDecisionParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RouteDecisionParser() {
    }

    /**
     * 一条有效路由意图。
     *
     * @param routeKey  与候选 route_key 对齐
     * @param subQuery  切给该子的子问题
     */
    public record Item(String routeKey, String subQuery) {
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
            unique.put(canonical.toLowerCase(Locale.ROOT), new Item(canonical, sub));
        }
        return new ArrayList<>(unique.values());
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

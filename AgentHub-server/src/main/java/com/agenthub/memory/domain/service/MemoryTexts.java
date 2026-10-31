package com.agenthub.memory.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.model.ContextTurn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 记忆正文的哈希、密钥拦截和抽取结果解析。所属域：memory。
 * 显式「记住」只做规则命中，命中后才调用抽取模型。
 */
public final class MemoryTexts {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key\\s*[:=]|secret\\s*[:=]|password\\s*[:=]|sk-[a-z0-9]{8,}|bearer\\s+[a-z0-9\\-._]{8,})");
    private static final Pattern REMEMBER = Pattern.compile("记住|以后默认|以后都按|请记住|帮我记住");
    private static final ObjectMapper JSON = new ObjectMapper();

    private MemoryTexts() {
    }

    /**
     * @param content 即将入库的正文
     * @throws BizException 像密钥时拒绝
     */
    public static void rejectSecret(String content) {
        if (content != null && SECRET.matcher(content).find()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记忆正文不能包含密钥");
        }
    }

    /**
     * @param content 正文
     * @return 去空白后的 SHA-256，用于合并相同偏好
     */
    public static String hash(String content) {
        String normalized = content == null ? "" : content.trim().replaceAll("\\s+", " ");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }

    /**
     * @param text 用户本轮原文
     * @return 是否像明确要求记住
     */
    public static boolean wantsRemember(String text) {
        return text != null && REMEMBER.matcher(text).find();
    }

    /**
     * @param expiredTime 过期时间，可空
     * @param fallbackSec 没有过期时间时的 TTL
     * @return 至少 1 秒
     */
    public static int ttlSeconds(LocalDateTime expiredTime, int fallbackSec) {
        if (expiredTime == null) {
            return Math.max(fallbackSec, 1);
        }
        long seconds = Duration.between(LocalDateTime.now(), expiredTime).getSeconds();
        return (int) Math.max(seconds, 1);
    }

    /**
     * 解析抽取模型输出。支持：纯数组、markdown 围栏、外层 preferences/items/memories、单对象。
     * content 兼容 text / fact / memory / preference / value。
     *
     * @param raw 模型输出
     * @return 稳定偏好；完全解析不出时为空列表
     */
    public static List<PreferenceDraft> parsePreferences(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = stripFence(raw.trim());
        JsonNode root = readJsonNode(text);
        if (root == null) {
            return List.of();
        }
        List<PreferenceDraft> rows = new ArrayList<>();
        if (root.isArray()) {
            collectDrafts(root, rows);
            return rows;
        }
        if (root.isObject()) {
            for (String key : List.of("preferences", "items", "memories", "data", "result")) {
                JsonNode nested = root.get(key);
                if (nested != null && nested.isArray()) {
                    collectDrafts(nested, rows);
                    return rows;
                }
            }
            PreferenceDraft single = draftOf(root);
            if (single != null) {
                rows.add(single);
            }
        }
        return rows;
    }

    /**
     * @param raw 模型原文
     * @return 去掉围栏后的文本；无围栏则原样
     */
    public static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence < 0) {
            return text;
        }
        int start = text.indexOf('\n', fence);
        int end = text.lastIndexOf("```");
        if (start >= 0 && end > start) {
            return text.substring(start + 1, end).trim();
        }
        return text.replace("```json", "").replace("```JSON", "").replace("```", "").trim();
    }

    /**
     * @param raw 模型原文（可含分析废话）
     * @return 是否实质为空结果（仅空白或字面 []）
     */
    public static boolean isEmptyExtractPayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String text = stripFence(raw).trim();
        return text.isEmpty() || "[]".equals(text);
    }

    private static JsonNode readJsonNode(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception ignored) {
            // 模型常在 JSON 前后夹分析文字，截取第一个数组或对象再试
        }
        int arrayOpen = text.indexOf('[');
        int arrayClose = text.lastIndexOf(']');
        if (arrayOpen >= 0 && arrayClose > arrayOpen) {
            try {
                return JSON.readTree(text.substring(arrayOpen, arrayClose + 1));
            } catch (Exception ignored) {
                // fall through
            }
        }
        int objOpen = text.indexOf('{');
        int objClose = text.lastIndexOf('}');
        if (objOpen >= 0 && objClose > objOpen) {
            try {
                return JSON.readTree(text.substring(objOpen, objClose + 1));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static void collectDrafts(JsonNode array, List<PreferenceDraft> rows) {
        for (JsonNode node : array) {
            PreferenceDraft draft = draftOf(node);
            if (draft != null) {
                rows.add(draft);
            }
        }
    }

    private static PreferenceDraft draftOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String content = firstText(node, "content", "text", "fact", "memory", "preference", "value");
        if (content.isBlank()) {
            return null;
        }
        PreferenceDraft draft = new PreferenceDraft();
        draft.setFactKey(blankToNull(firstText(node, "fact_key", "factKey", "key")));
        draft.setTitle(blankToNull(firstText(node, "title", "name", "label")));
        draft.setContent(content);
        draft.setImportance(node.path("importance").asInt(node.path("score").asInt(50)));
        return draft;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = textOf(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /**
     * @param turns 旧轮
     * @return 送给摘要或抽取模型的纯文本
     */
    public static String renderTurns(List<ContextTurn> turns) {
        StringBuilder text = new StringBuilder();
        if (turns == null) {
            return "";
        }
        for (ContextTurn turn : turns) {
            if (turn == null || turn.getText() == null || turn.getText().isBlank()) {
                continue;
            }
            String role = "assistant".equalsIgnoreCase(turn.getRole()) ? "助手" : "用户";
            text.append(role).append('：').append(turn.getText().trim()).append('\n');
        }
        return text.toString();
    }

    /**
     * 抽取模型给出的一条偏好。所属域：memory。
     * factKey 为稳定事实键；写入前须经 {@link FactKeyNormalizer} 归一。
     */
    public static final class PreferenceDraft {
        private String factKey;
        private String title;
        private String content;
        private int importance;

        public String getFactKey() {
            return factKey;
        }

        public void setFactKey(String factKey) {
            this.factKey = factKey;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public int getImportance() {
            return importance;
        }

        public void setImportance(int importance) {
            this.importance = importance;
        }
    }
}

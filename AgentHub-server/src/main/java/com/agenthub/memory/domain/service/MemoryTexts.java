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

    /**
     * L0 显式「记住」的默认正则片段。
     * <p>
     * yml {@code agenthub.memory.remember-patterns} 非空时整体替换本列表；
     * 这是代码默认值，不在 Agent 记忆页暴露，避免把快筛规则做成业务配置。
     */
    public static final List<String> DEFAULT_REMEMBER_PATTERNS = List.of(
            "记住", "请记住", "帮我记住", "以后默认", "以后都按",
            "记一下", "帮我记着", "别忘了", "存一下");

    /**
     * L0 误伤排除：这些短语里包含「记住」类词，但语义是询问/否定，必须先判排除。
     */
    public static final List<String> DEFAULT_REMEMBER_EXCLUSIONS = List.of(
            "记不住", "记住了吗", "还记得吗");

    /**
     * ttl_days 的硬上限（约 100 年）。超过一律按永久（0）处理：
     * 异常大值在库里等于永久，但会让 {@code now.plusDays} 溢出并把条目写成过去时间，反而立刻过期。
     */
    public static final int MAX_TTL_DAYS = 36500;

    private static final Pattern REMEMBER = Pattern.compile(String.join("|", DEFAULT_REMEMBER_PATTERNS));
    private static final Pattern REMEMBER_EXCLUSION =
            Pattern.compile(String.join("|", DEFAULT_REMEMBER_EXCLUSIONS));
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
     * L0 快筛：用户这句话是否像在明确要求「记住」。
     * <p>
     * 先判排除再判命中：「记住了吗」包含「记住」，但它是询问，不能进抽取。
     * 本方法<b>不按长度丢弃</b>，「记住它」这种极短指令必须能命中。
     *
     * @param text 用户本轮原文
     * @return 是否像明确要求记住
     */
    public static boolean wantsRemember(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (REMEMBER_EXCLUSION.matcher(text).find()) {
            return false;
        }
        return REMEMBER.matcher(text).find();
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
     * 由抽取给出的 ttl_days 算出库里的 expired_time。
     *
     * <p>口径：{@code ttl_days <= 0}（含缺省、非法）表示<b>永久</b>，返回 null，
     * 与「{@code expired_time IS NULL} 视为不过期」的召回条件一致；
     * 正整数按天累加，并压到 {@link #MAX_TTL_DAYS} 以内。
     *
     * @param ttlDays 有效天数，0/负数=永久
     * @param now     基准时间，空则取当前
     * @return 过期时间；永久返回 null
     */
    public static LocalDateTime expiredTimeOf(int ttlDays, LocalDateTime now) {
        if (ttlDays <= 0) {
            return null;
        }
        int days = Math.min(ttlDays, MAX_TTL_DAYS);
        return (now == null ? LocalDateTime.now() : now).plusDays(days);
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
        draft.setTtlDays(ttlDaysOf(node));
        return draft;
    }

    /**
     * 解析 ttl_days。缺省、非数字、负数、超过上限一律按 0（永久）。
     *
     * <p>为什么非法也按永久而不是丢弃：抽取模型偶尔把「永久」写成 -1 或 "长期"，
     * 因为一个格式小错就丢掉整条偏好，比多留一条永久记忆代价更大；过短的错值已被 0 兜住，
     * 不会出现「刚写进去就过期」。
     *
     * @param node 单条草稿对象
     * @return 有效天数；0 表示永久
     */
    private static int ttlDaysOf(JsonNode node) {
        for (String field : List.of("ttl_days", "ttlDays", "ttl")) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            int days;
            if (value.isNumber()) {
                days = value.asInt(0);
            } else {
                String text = value.asText("").trim();
                if (text.isEmpty()) {
                    continue;
                }
                try {
                    days = Integer.parseInt(text);
                } catch (NumberFormatException ex) {
                    return 0;
                }
            }
            if (days <= 0 || days > MAX_TTL_DAYS) {
                return 0;
            }
            return days;
        }
        return 0;
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
     * ttlDays 为有效天数：0=永久（expired_time 留空），正整数按天算过期时间。
     */
    public static final class PreferenceDraft {
        private String factKey;
        private String title;
        private String content;
        private int importance;
        private int ttlDays;

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

        /**
         * @return 有效天数；0 表示永久
         */
        public int getTtlDays() {
            return ttlDays;
        }

        /**
         * @param ttlDays 有效天数；0 或负数表示永久
         */
        public void setTtlDays(int ttlDays) {
            this.ttlDays = ttlDays;
        }
    }
}

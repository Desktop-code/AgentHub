package com.agenthub.runtime.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Optional;

/**
 * 解析评审环（CRITIC_LOOP）评审专家的判定输出。所属域：runtime。
 *
 * <p>判定契约（与前端/文档一致）：评审专家最终可见输出须可解析为
 * {@code {"decision":"ACCEPT"|"REVISE","feedback":"中文说明"}}；
 * 允许模型前后带少量废话，这里按现网惯用方式抽第一个 JSON 对象。
 *
 * <p>为什么解析失败一律按 ACCEPT：评审环是「有限回修」而不是「必须有结论」，
 * 把解析失败当成 REVISE 会在模型不听话时反复回修直到耗满轮次（甚至掩盖真正的回答）；
 * 失败信息由调用方记到 obs（{@code ma.critic_parse=fail}），用户侧仍拿到可用执行稿。
 */
public final class CriticVerdictParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 通过：终稿＝当前执行稿 */
    public static final String DECISION_ACCEPT = "ACCEPT";

    /** 需要回修：带上 feedback 再执行者跑一次 */
    public static final String DECISION_REVISE = "REVISE";

    /** REVISE 但评审没给细则时的固定中文提示，避免用空字符串喂回执行者 */
    public static final String DEFAULT_FEEDBACK = "请按评审意见改进（未提供细则）";

    private CriticVerdictParser() {
    }

    /**
     * 一条评审判定。
     *
     * @param decision ACCEPT | REVISE；解析失败/未知值恒为 ACCEPT
     * @param feedback 回修意见（REVISE 且空白时回落 {@link #DEFAULT_FEEDBACK}；ACCEPT 可为空）
     * @param parsed   是否成功抽到 JSON 判定块；false 时调用方应记 {@code ma.critic_parse=fail}
     */
    public record Verdict(String decision, String feedback, boolean parsed) {

        /**
         * @return 是否要求回修
         */
        public boolean revise() {
            return DECISION_REVISE.equals(decision);
        }

        /**
         * @return 可送回执行者的意见；ACCEPT 时为空串
         */
        public String feedbackOrEmpty() {
            return feedback == null ? "" : feedback;
        }
    }

    /**
     * 从评审专家输出中抽取判定。大小写不敏感；未知 decision 视为 ACCEPT。
     *
     * @param reply 评审专家最终可见输出；可空
     * @return 判定；永不为 null
     */
    public static Verdict parse(String reply) {
        Optional<JsonNode> root = extractJsonObject(reply);
        if (root.isEmpty() || !root.get().isObject()) {
            return new Verdict(DECISION_ACCEPT, "", false);
        }
        JsonNode node = root.get();
        String raw = textOf(node, "decision");
        if (raw.isEmpty()) {
            raw = textOf(node, "verdict");
        }
        String feedback = textOf(node, "feedback");
        // 只认 REVISE：其它任何值（含拼错、REJECT、空）都按 ACCEPT，防止一次误判把轮次耗光
        if (!DECISION_REVISE.equals(raw.toUpperCase(Locale.ROOT))) {
            return new Verdict(DECISION_ACCEPT, feedback, true);
        }
        return new Verdict(DECISION_REVISE, feedback.isEmpty() ? DEFAULT_FEEDBACK : feedback, true);
    }

    /**
     * 抽第一个 JSON 对象：容忍 markdown 代码块与前后废话，取首个 '{' 到末个 '}'。
     *
     * @param reply 模型原文
     * @return 解析成功的 JSON 根节点；没有 '{' 或解析失败为空
     */
    private static Optional<JsonNode> extractJsonObject(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        String text = reply.trim();
        // 模型常包一层 ```json 围栏：先剥围栏，避免把围栏当成 JSON 内容
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
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

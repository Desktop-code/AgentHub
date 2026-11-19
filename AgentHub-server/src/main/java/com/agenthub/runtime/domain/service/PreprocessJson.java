package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.PreprocessView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入口预处理步骤正文的序列化。所属域：runtime，纯逻辑。
 *
 * <p>同一份对象喂 SSE、内存快照和落库回放，字段名就是前端契约。
 * 出站走 {@link #frame}（对象，写入器只序列化一次）；落库走 {@link #payload}。
 */
public final class PreprocessJson {

    /**
     * 落库专用的素材键。不属于 SSE 帧：前端已有 {@code text} 可展示，
     * 这一份只给 {@code RunEventTurnSource} 跨轮回放时把票面 / 抽字正文拼回用户轮。
     */
    private static final String MATERIAL_FIELD = "material";

    private static final ObjectMapper JSON = new ObjectMapper();

    private PreprocessJson() {
    }

    /**
     * SSE 出站载荷。不要先收成字符串再交出站，否则会再包一层引号。
     *
     * @param view 预处理快照
     * @return 载荷对象
     */
    public static Map<String, Object> frame(PreprocessView view) {
        if (view == null) {
            return Map.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", view.status());
        body.put("tasks", view.tasks());
        body.put("hint", view.hint());
        body.put("summary", view.summary());
        body.put("text", view.text());
        return body;
    }

    /**
     * 落库正文。SSE 帧形状不变，只多落一个 {@code material} 字段供跨轮回放取用。
     *
     * @param view     预处理快照
     * @param material 附件素材（识图 / 转写 / 抽字）；空则不写这个键
     * @return JSON 字符串；无法序列化时退回摘要
     */
    public static String payload(PreprocessView view, String material) {
        if (view == null) {
            return "";
        }
        Map<String, Object> body = new LinkedHashMap<>(frame(view));
        // 只进落库，不进 SSE：前端已有 text 可展示，再推一份全文只会放大帧
        String facts = material == null ? "" : material.trim();
        if (!facts.isEmpty()) {
            body.put(MATERIAL_FIELD, facts);
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            return view.summary();
        }
    }

    /**
     * 从落库正文取回素材。旧事件没有这个字段，返回空串。
     *
     * @param raw 事件正文
     * @return 素材正文；缺失或解析失败为空串
     */
    public static String materialOf(String raw) {
        if (raw == null || raw.isBlank() || raw.charAt(0) != '{') {
            return "";
        }
        try {
            var node = JSON.readTree(raw.trim());
            var field = node.get(MATERIAL_FIELD);
            return field == null || field.isNull() ? "" : field.asText("");
        } catch (JsonProcessingException ex) {
            return "";
        }
    }

    /**
     * 落库正文。
     *
     * @param view 预处理快照
     * @return JSON 字符串；无法序列化时退回摘要
     */
    public static String payload(PreprocessView view) {
        if (view == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(frame(view));
        } catch (JsonProcessingException ex) {
            return view.summary();
        }
    }

    /**
     * 摘要列：进行中用 hint，完成/失败用 summary。
     *
     * @param view 预处理快照
     * @return 不超过摘要上限的短句
     */
    public static String summaryOf(PreprocessView view) {
        if (view == null) {
            return "";
        }
        String raw = PreprocessView.RUNNING.equals(view.status()) ? view.hint() : view.summary();
        if (raw == null || raw.isBlank()) {
            raw = view.text();
        }
        return TrialTexts.summary(raw);
    }

    /**
     * 从落库 / 快照正文还原。半截 JSON 放弃，不让回放整轮失败。
     *
     * @param raw 事件正文
     * @return 快照；解析失败为空
     */
    public static PreprocessView parse(String raw) {
        if (raw == null || raw.isBlank() || raw.charAt(0) != '{') {
            return null;
        }
        try {
            var node = JSON.readTree(raw.trim());
            List<String> tasks = new ArrayList<>();
            if (node.has("tasks") && node.get("tasks").isArray()) {
                node.get("tasks").forEach(item -> {
                    if (item != null && item.isTextual() && !item.asText().isBlank()) {
                        tasks.add(item.asText().trim());
                    }
                });
            }
            return new PreprocessView(
                    text(node, "status"),
                    tasks,
                    text(node, "hint"),
                    text(node, "summary"),
                    text(node, "text"));
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("");
    }
}

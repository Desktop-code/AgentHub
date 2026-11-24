package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.AgentChoice;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「可点选项」的 JSON 解析与序列化。所属域：runtime，纯逻辑。
 *
 * <p>形状（字段名即前后端契约，改名属协议变更）：
 * <pre>
 * {"question":"你希望用哪个技术栈？","options":[{"id":"vue3","label":"Vue3 + Vite + TS"}]}
 * </pre>
 *
 * <p>两条来源共用本类：工具 {@code agenthub_choice} 的参数、后端整理模型输出的 JSON。
 * 历史会话里的 {@code agenthub-choice} 机器块仍由 {@link #parse(String)} 兼容读取
 * （新写入不再产生机器块）。
 *
 * <p>为什么由后端解析：断点续作必须校验「用户点的选项确实出现在上一轮回答里」，
 * 不能信任前端回传的 label；边界（id 唯一、2~6 个）也必须只有一份口径。
 *
 * <p>只做解析与校验，不负责渲染与落库动作（落库由 AppService 发起）。
 * 线程安全：纯静态方法，无状态。
 */
public final class WorkbenchChoiceJson {

    /** 围栏语言标记；前端只认这一种，避免普通代码块被误当选项 */
    public static final String FENCE_LANG = "agenthub-choice";

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkbenchChoiceJson() {
    }

    /**
     * 从一轮助手正文里摘出选项块。
     *
     * @param raw 助手正文（Markdown）
     * @return 命中且结构合法时：去掉机器块后的正文 + 选项；否则为空
     */
    public static Optional<Parsed> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        int open = raw.indexOf("```" + FENCE_LANG);
        if (open < 0) {
            return Optional.empty();
        }
        // 语言标记后必须紧跟换行（允许尾随空白）：避免 ```` ```agenthub-choiceXYZ ```` 这类同前缀块被误认
        int bodyStart = raw.indexOf('\n', open);
        if (bodyStart < 0) {
            return Optional.empty();
        }
        String info = raw.substring(open + 3 + FENCE_LANG.length(), bodyStart);
        if (!info.trim().isEmpty()) {
            return Optional.empty();
        }
        int close = raw.indexOf("```", bodyStart + 1);
        if (close < 0) {
            // 半截块（还在流式 / 模型截断）：不当选项，正文保持原样，由前端按未闭合代码块渲染
            return Optional.empty();
        }
        AgentChoice choice = readJson(raw.substring(bodyStart + 1, close));
        if (choice == null) {
            return Optional.empty();
        }
        // 机器块从正文里摘掉：前端不再展示原始 JSON；块后若还有正文，按原顺序接上
        String text = (raw.substring(0, open) + raw.substring(close + 3)).trim();
        return Optional.of(new Parsed(text, choice));
    }

    /**
     * 只去掉机器块，保留正文（给会话列表预览这类「不能出现围栏」的地方用）。
     *
     * @param raw 助手正文；可空
     * @return 去掉机器块后的正文；没有块时原样返回（仅 trim）
     */
    public static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        return parse(raw).map(Parsed::text).orElse(raw.trim());
    }

    /**
     * 解析一份结构化选项（工具参数 / 后端整理结果 / CHOICE 事件正文共用）。
     *
     * <p>只认 {@code {"question":…,"options":[{"id":…,"label":…}]}}；可容忍前后包裹的
     * Markdown 围栏或说明文字（模型偶发不肯只吐 JSON），但 JSON 本体必须严格合法。
     *
     * @param raw JSON 原文，可空
     * @return 合法结构；不合规时为空（整份作废，绝不半信半疑放行）
     */
    public static Optional<AgentChoice> parseJson(String raw) {
        AgentChoice choice = readJson(raw);
        return choice == null ? Optional.empty() : Optional.of(choice);
    }

    /**
     * 把选项结构写成紧凑 JSON，用于 CHOICE 事件正文。
     *
     * @param choice 选项结构，可空
     * @return 紧凑 JSON；空结构返回空串（调用方据此跳过落库）
     */
    public static String write(AgentChoice choice) {
        if (choice == null) {
            return "";
        }
        // 等待态必须有可点选项；回复态可以没有选项（选项已清掉），但必须有回复文案
        if (!choice.usable() && !choice.hasReply()) {
            return "";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", choice.question() == null ? "" : choice.question());
        List<Map<String, Object>> options = new ArrayList<>();
        for (AgentChoice.Option option : choice.options()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", option.id());
            one.put("label", option.label());
            options.add(one);
        }
        body.put("options", options);
        // 只有停等 / 已答复时才多出这两个字段：普通选项的 JSON 与旧协议逐字一致
        if (choice.hasReply()) {
            body.put("reply", choice.reply());
        }
        if (choice.waiting()) {
            body.put("waiting", true);
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            // 结构只含字符串，正常不会失败；失败时返回空串，宁可不落库也不抛进主链路
            return "";
        }
    }

    /**
     * 解析 JSON 并做边界校验。
     *
     * @param body 原文（围栏内的 JSON，或整理模型的输出）
     * @return 合法选项；任何一项不合格都返回 null（整块作废，不半信半疑地放行）
     */
    private static AgentChoice readJson(String body) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            return null;
        }
        // 模型常把 JSON 包在围栏 / 解释文字里：只取最外层的一对花括号，避免因此整份作废
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        text = text.substring(start, end + 1);
        try {
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject()) {
                return null;
            }
            String question = root.path("question").asText("");
            String reply = root.path("reply").asText("").trim();
            boolean waiting = root.path("waiting").asBoolean(false);
            JsonNode array = root.path("options");
            // 回复态允许没有 options（用户答复后选项已清掉）；没有 options 又没有 reply 才算非法
            if (!array.isArray()) {
                return reply.isEmpty() ? null : new AgentChoice(question, List.of(), reply, false);
            }
            List<AgentChoice.Option> options = new ArrayList<>();
            for (JsonNode one : array) {
                if (one == null || !one.isObject()) {
                    return reply.isEmpty() ? null : new AgentChoice(question, List.of(), reply, false);
                }
                options.add(new AgentChoice.Option(one.path("id").asText(""), one.path("label").asText("")));
            }
            // 统一走 AgentChoice.of：边界（非空 / id 唯一 / 2~6 项）与工具、整理结果同口径
            AgentChoice base = AgentChoice.of(question, options);
            if (base == null) {
                return reply.isEmpty() ? null : new AgentChoice(question, List.of(), reply, false);
            }
            if (waiting) {
                return new AgentChoice(base.question(), base.options(), reply, true);
            }
            return reply.isEmpty() ? base : new AgentChoice(base.question(), base.options(), reply, false);
        } catch (Exception ex) {
            // 模型偶发输出非法 JSON：整块忽略，正文照常展示，不抛进运行主链路
            return null;
        }
    }

    /**
     * 解析结果。
     *
     * @param text   去掉机器块后的助手正文（已 trim）
     * @param choice 选项结构
     */
    public record Parsed(String text, AgentChoice choice) {
    }
}

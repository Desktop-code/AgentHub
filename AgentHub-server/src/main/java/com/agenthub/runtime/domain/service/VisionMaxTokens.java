package com.agenthub.runtime.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 视觉（OCR）出站的 max_tokens 取值规则。所属域：runtime（domain service）。
 *
 * <p>为什么要单独成类：视觉模型的输出上限常比对话模型小得多，且各家中转给的区间不一
 * （例如只接受 {@code [1,1024]}）。一旦超出，上游直接 400，而错误只表现为一句状态码，
 * 很容易被误当成「图片有问题」。把「默认多少」这件事收口在这里，改默认值时只有一处需要动。
 *
 * <p>关键约定：<b>只给默认，不做上限钳制</b>。
 * <ul>
 *   <li>用户/实例显式配了更大值（如 4096）就原样发出——不同模型/中转的真实上限不同，
 *       平台无权替用户决定，钳到 1024 会让「我明明配了 4096 却没生效」变成更隐蔽的问题；</li>
 *   <li>只有「未配置」或「明显非法（≤0）」时才回落到 {@link #DEFAULT}。</li>
 * </ul>
 *
 * 线程安全：无状态，静态纯函数。
 */
public final class VisionMaxTokens {

    /**
     * 视觉识别默认输出上限。取 1024 而不是更高：多数视觉模型的输出区间是 {@code [1,1024]}，
     * 而「提取图片文字」本身不需要长篇输出，1024 足够，且不会因超限被上游拒绝。
     */
    public static final int DEFAULT = 1024;

    /** extraJson 里允许的两种键名写法；下划线是 OpenAI 风格，驼峰兼容前端手填 */
    private static final String[] KEYS = {"max_tokens", "maxTokens"};

    private static final ObjectMapper JSON = new ObjectMapper();

    private VisionMaxTokens() {
    }

    /**
     * 解析实际要发出的 max_tokens。
     *
     * @param configured 实例显式配置值，可空
     * @return 配置值原样返回（不做上限钳制）；未配置或 ≤0 时返回 {@link #DEFAULT}
     */
    public static int resolve(Integer configured) {
        // 上游对 0 / 负数一律拒绝；这种情况按「没配」处理，而不是把明显非法的值发出去
        return configured == null || configured < 1 ? DEFAULT : configured;
    }

    /**
     * 从模型实例的 extraJson 读 max_tokens。
     *
     * @param extraJson 扩展 JSON 文本，可空
     * @return 配置值；未配置、非法 JSON 或非正数时返回 null（调用方按默认处理）
     */
    public static Integer parse(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(extraJson);
            if (root == null || !root.isObject()) {
                return null;
            }
            for (String key : KEYS) {
                JsonNode node = root.get(key);
                if (node == null || node.isNull()) {
                    continue;
                }
                // 允许写成字符串（手填 JSON 常见），只接受整体为正整数的情况
                int value = node.isNumber() ? node.asInt() : parsePositive(node.asText());
                if (value > 0) {
                    return value;
                }
            }
        } catch (Exception ex) {
            // 坏 JSON 不该让整轮 OCR 失败：当作未配置，走默认值
            return null;
        }
        return null;
    }

    /**
     * @param raw 文本形式的数值，可空
     * @return 正整数；无法解析或非正数时返回 -1
     */
    private static int parsePositive(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}

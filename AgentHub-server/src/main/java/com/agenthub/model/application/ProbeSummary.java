package com.agenthub.model.application;

/**
 * 连通测试摘要。所属域：model。
 * 写入 last_test_msg 之前去掉本次 Key，并截到列宽 255。
 */
public final class ProbeSummary {

    /** 与 model_instance.last_test_msg 的 varchar(255) 对齐。 */
    static final int MAX_LENGTH = 255;

    private ProbeSummary() {
    }

    /**
     * @param message 适配器给出的说明，失败响应里仍可能夹带 Key
     * @param apiKey  本次明文，只用于替换，不会出现在返回值里
     * @return 不含 Key 的摘要；空白说明返回「无说明」
     */
    public static String of(String message, String apiKey) {
        String text = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
        if (apiKey != null && !apiKey.isBlank()) {
            text = text.replace(apiKey, "***");
        }
        text = text.trim();
        if (text.isEmpty()) {
            return "无说明";
        }
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH);
    }
}

package com.agenthub.tool.application;

/**
 * 同步摘要脱敏。所属域：tool。last_sync_msg 和异常文案都走这里，禁止留下鉴权头。
 */
public final class SyncText {

    private SyncText() {
    }

    /**
     * 去掉明文头、头的值，以及 Bearer token。过长时截断。
     *
     * @param message 原始说明，可空
     * @param secret  本次用过的鉴权头明文，可空
     * @return 可写入 last_sync_msg 或返回前端的句子
     */
    public static String safe(String message, String secret) {
        String text = message == null || message.isBlank() ? "同步失败" : message;
        if (secret != null && !secret.isBlank()) {
            text = text.replace(secret, "***");
            int colon = secret.indexOf(':');
            if (colon >= 0) {
                String value = secret.substring(colon + 1).trim();
                if (value.length() >= 4) {
                    text = text.replace(value, "***");
                }
            }
        }
        text = text.replaceAll("(?i)bearer\\s+\\S+", "Bearer ***");
        return clip(text, 255);
    }

    /**
     * @param message 已脱敏的句子
     * @param max     最大字符数
     * @return 截断后的句子
     */
    public static String clip(String message, int max) {
        if (message == null) {
            return "";
        }
        return message.length() <= max ? message : message.substring(0, max);
    }
}

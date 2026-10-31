package com.agenthub.obs.domain.service;

import java.util.regex.Pattern;

/**
 * 调用正文的打码。所属域：obs。
 * 只替换密钥形态，不按长度截断。title、input_text、output_text、error_summary 都走这里。
 * name 列是 VARCHAR(128)，不是 TEXT，超长会写不进去，所以单独收口列宽。
 */
public final class ObsTexts {

    /**
     * JSON 里这些键的值改成 ***。只匹配完整键名，避免把 keyword 误伤成 key。
     */
    private static final Pattern JSON_SECRET_FIELD = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|secret|token|password|passwd|authorization|cipher|access[_-]?token|key)\"\\s*:\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|-?\\d+(?:\\.\\d+)?|true|false|null)");

    /** obs_span.name 的列宽 */
    public static final int NAME_LIMIT = 128;

    private ObsTexts() {
    }

    /**
     * 去掉常见密钥，保留其余字符和换行。
     *
     * @param raw 原文，可空
     * @return 打码后的文本；空白为 null
     */
    public static String mask(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = JSON_SECRET_FIELD.matcher(raw).replaceAll("$1\"***\"");
        text = text.replaceAll("(?i)bearer\\s+\\S+", "Bearer ***");
        text = text.replaceAll("(?i)(api[_-]?key|secret|token|cipher|authorization|password|passwd)\\s*[:=]\\s*\\S+", "$1=***");
        text = text.replaceAll("sk-[A-Za-z0-9_\\-]{6,}", "sk-***");
        text = text.replaceAll("[A-Za-z0-9+/]{16,}={0,2}:[A-Za-z0-9+/]{16,}={0,2}", "***");
        return text.isBlank() ? null : text;
    }

    /**
     * 展示名必须放进 VARCHAR(128)。正文不走这个方法。
     *
     * @param raw      原始名称，可空
     * @param fallback 空名称时的替代
     * @return 不超过 128 个字符的名称
     */
    public static String fitName(String raw, String fallback) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            text = fallback == null || fallback.isBlank() ? "step" : fallback.trim();
        }
        return text.length() <= NAME_LIMIT ? text : text.substring(0, NAME_LIMIT);
    }
}

package com.agenthub.runtime.domain.service;

import java.util.regex.Pattern;

/**
 * 试跑摘要的脱敏和截断。所属域：runtime。
 * session.last_msg_preview、error_summary 和 event.summary 都必须经过这里。
 */
public final class TrialTexts {

    /** agent_run_session.last_msg_preview */
    public static final int PREVIEW_LIMIT = 255;
    /** agent_run_session.session_title。会话标题只在左栏一行内展示，64 字节列宽内留足余量 */
    public static final int TITLE_LIMIT = 64;
    /** agent_run_event.summary 与 error_summary */
    public static final int SUMMARY_LIMIT = 500;
    /** content_text 默认上限。配置可覆盖，超出后注明已截断 */
    public static final int CONTENT_LIMIT = 32 * 1024;
    /** 正文被截断时附在末尾，便于前端看出不完整 */
    public static final String TRUNCATED_MARK = "已截断";
    /**
     * JSON 里这些键的值一律打成 ***。
     * 只匹配完整键名，避免把 keyword 误伤成 key。
     */
    private static final Pattern JSON_SECRET_FIELD = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|secret|token|password|passwd|authorization|cipher|access[_-]?token|key)\"\\s*:\\s*)"
                    + "(\"(?:\\\\.|[^\"\\\\])*\"|-?\\d+(?:\\.\\d+)?|true|false|null)");

    private TrialTexts() {
    }

    /**
     * 写成事件摘要。空白变成空串，超长截断。
     *
     * @param raw 原始文本，可空
     * @return 不超过 500 字的脱敏文本
     */
    public static String summary(String raw) {
        return clip(redact(raw), SUMMARY_LIMIT);
    }

    /**
     * 写成会话预览。
     *
     * @param raw 原始文本，可空
     * @return 不超过 255 字的脱敏文本
     */
    public static String preview(String raw) {
        return clip(redact(raw), PREVIEW_LIMIT);
    }

    /**
     * 写成 content_text。先脱敏，再按上限截断。
     * 不折叠空格和换行，避免把助手 Markdown 或代码缩进剥掉。
     *
     * @param raw   原始正文，可空
     * @param limit 最大字符数，含「已截断」标记；小于等于 0 时用 {@link #CONTENT_LIMIT}
     * @return 脱敏后的正文；空输入返回空串
     */
    public static String content(String raw, int limit) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = redact(raw).replace("\r\n", "\n").replace('\r', '\n');
        int max = limit > 0 ? limit : CONTENT_LIMIT;
        if (text.length() <= max) {
            return text;
        }
        String mark = "\n" + TRUNCATED_MARK;
        int room = max - mark.length();
        if (room < 1) {
            return TRUNCATED_MARK;
        }
        return text.substring(0, room) + mark;
    }

    /**
     * 去掉常见密钥形态。工具入参请先走 {@link #redactArgs(String)}。
     *
     * @param raw 原始文本，可空
     * @return 脱敏后的文本；空输入返回空串
     */
    public static String redact(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw;
        text = text.replaceAll("(?i)bearer\\s+\\S+", "Bearer ***");
        text = text.replaceAll("(?i)(api[_-]?key|secret|token|cipher|authorization|password|passwd)\\s*[:=]\\s*\\S+", "$1=***");
        text = text.replaceAll("sk-[A-Za-z0-9_\\-]{6,}", "sk-***");
        text = text.replaceAll("[A-Za-z0-9+/]{16,}={0,2}:[A-Za-z0-9+/]{16,}={0,2}", "***");
        return text;
    }

    /**
     * 工具入参脱敏。JSON 键名命中 Key / Token / Authorization / 密码时，值改成 ***。
     *
     * @param raw 引擎给出的参数片段，可空
     * @return 可展示的参数；空输入返回空串
     */
    public static String redactArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String masked = JSON_SECRET_FIELD.matcher(raw.trim()).replaceAll("$1\"***\"");
        return redact(masked).trim();
    }

    /**
     * 对话区里的工具行。没有参数时不编造花括号。
     *
     * @param name      工具名，空则写成未命名工具
     * @param arguments 入参，会再脱敏一次
     * @return 例如「调用工具 weather，参数 {"city":"北京"}」
     */
    public static String toolDisplay(String name, String arguments) {
        String safe = name == null || name.isBlank() ? "未命名工具" : name.trim();
        String args = redactArgs(arguments);
        if (args.isEmpty()) {
            return "调用工具 " + safe;
        }
        return "调用工具 " + safe + "，参数 " + args;
    }

    /**
     * 只折叠行内空白，换行留下来。抽屉里的助手回复要靠换行排标题和列表。
     * 超长仍截断，省略号占一个字符。
     *
     * @param text 已处理文本
     * @param max  最大字符数，含省略号
     * @return 截断结果
     */
    public static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replaceAll("[ \\t\\x0B\\f]+", " ");
        normalized = normalized.replaceAll(" *\\n *", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n").trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        if (max <= 1) {
            return "…";
        }
        return normalized.substring(0, max - 1) + "…";
    }
}

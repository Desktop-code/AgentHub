package com.agenthub.obs.domain.service;

/**
 * obs span/trace 的 attrs_json 小工具。所属域：obs。
 * 目前只约定可选键 brief（人话摘要）；无则不强行编造。
 */
public final class ObsAttrs {

    private ObsAttrs() {
    }

    /**
     * 写入或覆盖 brief，其它简单 JSON 在仅含 brief 场景够用。
     *
     * @param existing 已有 attrs，可空
     * @param brief    人话摘要，可空
     * @return JSON；无 brief 时返回 existing
     */
    public static String withBrief(String existing, String brief) {
        if (brief == null || brief.isBlank()) {
            return existing;
        }
        String escaped = escapeJson(brief.trim());
        if (existing == null || existing.isBlank()) {
            return "{\"brief\":\"" + escaped + "\"}";
        }
        String trimmed = existing.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && !trimmed.contains("\"brief\"")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) {
                return "{\"brief\":\"" + escaped + "\"}";
            }
            return "{" + inner + ",\"brief\":\"" + escaped + "\"}";
        }
        return "{\"brief\":\"" + escaped + "\"}";
    }

    /**
     * @param attrsJson attrs_json
     * @return brief；没有则为 null
     */
    public static String briefOf(String attrsJson) {
        if (attrsJson == null || attrsJson.isBlank()) {
            return null;
        }
        String key = "\"brief\"";
        int at = attrsJson.indexOf(key);
        if (at < 0) {
            return null;
        }
        int colon = attrsJson.indexOf(':', at + key.length());
        if (colon < 0) {
            return null;
        }
        int startQuote = attrsJson.indexOf('"', colon + 1);
        if (startQuote < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int i = startQuote + 1; i < attrsJson.length(); i++) {
            char c = attrsJson.charAt(i);
            if (c == '\\' && i + 1 < attrsJson.length()) {
                value.append(attrsJson.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') {
                break;
            }
            value.append(c);
        }
        return value.length() == 0 ? null : value.toString();
    }

    /**
     * 把本轮注入统计写进 attrs。右栏据此看出工具、Skill 和是否截断。
     *
     * @param existing 已有 attrs，可空
     * @param tools    进入提示词的工具数
     * @param skills   进入提示词的 Skill 数
     * @param chars    各类字符合计
     * @param truncated 是否发生截断或降级
     * @return 合并后的 JSON
     */
    public static String withInjection(String existing, int tools, int skills, int chars, boolean truncated) {
        String fields = "\"tools_injected\":" + tools
                + ",\"skills_injected\":" + skills
                + ",\"prompt_chars\":" + chars
                + ",\"truncated\":" + truncated;
        if (existing == null || existing.isBlank()) {
            return "{" + fields + "}";
        }
        String trimmed = existing.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && !trimmed.contains("\"tools_injected\"")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) {
                return "{" + fields + "}";
            }
            return "{" + inner + "," + fields + "}";
        }
        return "{" + fields + "}";
    }

    /**
     * @param attrsJson attrs
     * @param name      整数字段名
     * @return 没有该字段时为 null
     */
    public static Integer intField(String attrsJson, String name) {
        if (attrsJson == null || name == null) {
            return null;
        }
        String key = "\"" + name + "\":";
        int at = attrsJson.indexOf(key);
        if (at < 0) {
            return null;
        }
        int start = at + key.length();
        int end = start;
        while (end < attrsJson.length() && (Character.isDigit(attrsJson.charAt(end)) || attrsJson.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Integer.parseInt(attrsJson.substring(start, end));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * @param attrsJson attrs
     * @return truncated；没有该字段时为 null
     */
    public static Boolean truncated(String attrsJson) {
        if (attrsJson == null || !attrsJson.contains("\"truncated\"")) {
            return null;
        }
        return attrsJson.contains("\"truncated\":true");
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}

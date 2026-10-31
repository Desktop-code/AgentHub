package com.agenthub.admin.application;

/**
 * 把用户输入收成安全的 LIKE 片段。所属域：admin。
 * MySQL 默认把 \ 当转义符，这里去掉 % 和 _ 的通配含义。
 */
public final class LikeTexts {

    private LikeTexts() {
    }

    /**
     * @param raw 用户输入，空白视为不筛选
     * @return 可放进 LIKE 的文本；不需要筛选时返回 null
     */
    public static String contains(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

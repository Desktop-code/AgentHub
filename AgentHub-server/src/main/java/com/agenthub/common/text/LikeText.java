package com.agenthub.common.text;

/**
 * 把用户输入收成安全的 LIKE 片段。所属域：common（纯函数，无状态、无依赖）。
 *
 * <p>MySQL 默认把反斜杠当转义符：先转义 {@code \}，再把 {@code %} / {@code _} 各自加上反斜杠，
 * 这样用户输入里的通配符只按字面匹配，避免模糊查询被输入串成「匹配任意」。
 *
 * <p>约束：本类不做 trim 之外的正规化（不改大小写、不折叠空白），
 * 各 Repository 原先的 likeText 逐字等价；行为变更应另开需求，不在本类里顺手加规则。
 */
public final class LikeText {

    /** 工具类禁止实例化。 */
    private LikeText() {
    }

    /**
     * 生成 LIKE 用的转义文本。
     *
     * @param raw 用户输入；null 或纯空白视为「不筛选」
     * @return 已转义 {@code \} {@code %} {@code _} 的文本；不需要筛选时返回 null
     */
    public static String contains(String raw) {
        // 空白与 null 一律返回 null：调用方据此跳过 like 条件，避免拼出 %% 全表扫描
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 必须最先转义反斜杠，否则后续插入的转义符会被二次转义
        return raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

package com.agenthub.workflow.domain.enums;

/**
 * 人工待办状态。所属域：workflow 的 domain。
 * <p>OPEN 只允许一条有效待办（表上有 node_run 唯一键）；DONE 与 EXPIRED 都是终态。
 */
public enum HumanTaskStatus {

    /** 待处理 */
    OPEN,
    /** 已由人或超时策略决定 */
    DONE,
    /** 超时自动决定的归档标记（result_json 里带 decidedByPolicy） */
    EXPIRED;

    /**
     * @return 是否终态
     */
    public boolean terminal() {
        return this == DONE || this == EXPIRED;
    }

    /**
     * @param raw 库值
     * @return 枚举；空或未知为 null
     */
    public static HumanTaskStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return HumanTaskStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

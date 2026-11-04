package com.agenthub.workflow.domain.enums;

/**
 * 工作流运行实例状态。所属域：workflow 的 domain。
 * <p>迁移规则集中在 {@code WorkflowStateMachine}，枚举本身只承载「是什么」。
 * <p>本刀不做 TIMER / COMPENSATING：定时唤醒与补偿链属 P1。
 */
public enum WorkflowRunStatus {

    /** 已创建，尚未开始调度 */
    CREATED,
    /** 正在调度 / 执行节点 */
    RUNNING,
    /** 停在人工节点，等 approve / reject 或超时策略 */
    PAUSED_HUMAN,
    /** 已到达 END 且无阻断失败 */
    SUCCESS,
    /** 失败收束（节点失败且无错误边、墙钟、取消以外的终止） */
    FAILED,
    /** 协作式取消 */
    CANCELLED;

    /**
     * 是否为终态；终态不允许再迁移。
     *
     * @return true 表示 SUCCESS / FAILED / CANCELLED
     */
    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }

    /**
     * 宽松解析，未知值返回 null（由调用方决定报错口径）。
     *
     * @param raw 库值
     * @return 枚举；空或未知为 null
     */
    public static WorkflowRunStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WorkflowRunStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

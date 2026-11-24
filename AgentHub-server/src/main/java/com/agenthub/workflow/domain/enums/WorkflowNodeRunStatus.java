package com.agenthub.workflow.domain.enums;

/**
 * 节点运行态。所属域：workflow 的 domain。
 * <p>进入调度前预建 PENDING 行，画布据此把「未走到」画成灰色。
 * <p>WAITING_HUMAN 只出现在 HUMAN 节点；取消时 RUNNING / WAITING_HUMAN 收成 CANCELLED。
 */
public enum WorkflowNodeRunStatus {

    /** 尚未轮到（或前置边还未全部判定） */
    PENDING,
    /** 正在执行 */
    RUNNING,
    /** 成功 */
    SUCCESS,
    /** 失败（若走了 ERROR 边，run 仍可能继续） */
    FAILED,
    /** 分支未被选中，跳过 */
    SKIPPED,
    /** 人工节点等待处理 */
    WAITING_HUMAN,
    /**
     * 对话回复节点等待当前会话的用户下一句。
     *
     * <p>问句已写进本节点产出并投影进工作流卡正文；用户下一句送回同一条 run 后，
     * 本节点迁 SUCCESS 并按出边继续。与 {@link #WAITING_HUMAN} 分开，避免与人工待办混淆。
     */
    WAITING_REPLY,
    /** 被取消 */
    CANCELLED;

    /**
     * @return 是否已结束（不再需要调度）
     */
    public boolean terminal() {
        return this == SUCCESS || this == FAILED || this == SKIPPED || this == CANCELLED;
    }

    /**
     * 是否在等外部输入（人工处理 / 对话回复）之后才能继续。
     *
     * @return true 表示 WAITING_HUMAN / WAITING_REPLY
     */
    public boolean waiting() {
        return this == WAITING_HUMAN || this == WAITING_REPLY;
    }

    /**
     * @param raw 库值
     * @return 枚举；空或未知为 null
     */
    public static WorkflowNodeRunStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WorkflowNodeRunStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

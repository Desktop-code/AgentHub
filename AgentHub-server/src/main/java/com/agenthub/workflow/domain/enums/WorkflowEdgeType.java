package com.agenthub.workflow.domain.enums;

/**
 * 工作流边类型。所属域：workflow 的 domain。
 * <p>COND/DEFAULT 只用于 CONDITION 出口；ERROR 用于失败分支；CANCEL 可选，本刀只存盘。
 */
public enum WorkflowEdgeType {

    /** 成功后默认流转 */
    NORMAL,
    /** 条件为真时走；同一 CONDITION 的多条 COND 互斥 */
    COND,
    /** 条件皆假时兜底；CONDITION 必须有且仅有一条 */
    DEFAULT,
    /** 节点失败且未被吞掉时走 */
    ERROR,
    /** 取消信号专用汇合；只存盘，不执行 */
    CANCEL,
    /** 循环节点进入循环体；只能从循环节点拉出 */
    LOOP_BODY,
    /** 声明的回边，必须指向循环节点；未声明成这种边的环不能保存 */
    LOOP_BACK,
    /** 次数用尽后离开循环；失败出路不使用这条边 */
    LOOP_EXIT;

    /**
     * 解析边类型，大小写不敏感，容忍首尾空白。
     *
     * @param raw 图中 type 字段，可空
     * @return 合法枚举；空值或未知类型返回 null（由校验器记 error）
     */
    public static WorkflowEdgeType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WorkflowEdgeType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 默认边类型：未填时按 NORMAL 处理，避免前端连线忘写类型即报错。
     *
     * @param raw 图中 type 字段，可空
     * @return 解析成功返回枚举，否则返回 NORMAL
     */
    public static WorkflowEdgeType parseOrDefault(String raw) {
        WorkflowEdgeType parsed = parse(raw);
        return parsed == null ? NORMAL : parsed;
    }
}

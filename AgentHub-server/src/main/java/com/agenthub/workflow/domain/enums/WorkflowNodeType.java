package com.agenthub.workflow.domain.enums;

/**
 * 工作流节点类型。所属域：workflow 的 domain。
 * <p>P0 只落地 START/END/AGENT/TOOL/CONDITION/PARALLEL_FORK/PARALLEL_JOIN/HUMAN；
 * 其余类型在画布面板灰显并标注 P1，手改入库由校验器拒绝。
 */
public enum WorkflowNodeType {

    /** 唯一入口；注入 run 输入 */
    START,
    /** 结束节点，可多个；汇总输出映射 */
    END,
    /** 调已启用 Agent（禁止绑 ROUTER） */
    AGENT,
    /** 直调单个 tool_item，跳过 Agent 循环 */
    TOOL,
    /** 条件分支；出口必须有且仅有一条 DEFAULT 边 */
    CONDITION,
    /** 并行分叉；与 PARALLEL_JOIN 按 forkGroupId 成对 */
    PARALLEL_FORK,
    /** 并行汇合；与 PARALLEL_FORK 按 forkGroupId 成对 */
    PARALLEL_JOIN,
    /** 人工卡点；必须配置超时策略 */
    HUMAN,
    /** 知识检索；P1 */
    KB_RETRIEVE,
    /** 技能注入；P1 */
    SKILL_INJECT,
    /** 子图；P1，且永久禁止跨租户 */
    SUBGRAPH,
    /** 列表映射；P1 */
    MAP,
    /** 列表归约；P1 */
    REDUCE,
    /** 定时等待；P1 */
    TIMER,
    /** 补偿处理器；P1 */
    COMPENSATE;

    /**
     * 解析节点类型，大小写不敏感，容忍首尾空白。
     *
     * @param raw 图中 type 字段，可空
     * @return 合法枚举；空值或未知类型返回 null（由校验器记 error，不在此抛异常）
     */
    public static WorkflowNodeType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WorkflowNodeType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 是否属于本刀（P0）支持的类型。
     *
     * @return true 表示可建图、可发布
     */
    public boolean p0Supported() {
        return switch (this) {
            case START, END, AGENT, TOOL, CONDITION, PARALLEL_FORK, PARALLEL_JOIN, HUMAN -> true;
            default -> false;
        };
    }
}

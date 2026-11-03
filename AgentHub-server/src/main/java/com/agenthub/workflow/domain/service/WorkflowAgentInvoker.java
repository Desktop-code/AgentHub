package com.agenthub.workflow.domain.service;

/**
 * AGENT 节点执行口。所属域：workflow 的 domain。
 * <p>为什么用窄接口而不是直接依赖 runtime：引擎（含单测）只关心「给 agentId + 文本，
 * 拿回助手正文」；真实现放在 infra，复用现网试跑链路，禁止第二套 ReAct 循环。
 * <p>实现方约定：只允许 REACT / PLAN_AND_EXECUTE 等叶子 Agent，ROUTER 由引擎拒绝；
 * 失败抛 {@link com.agenthub.workflow.domain.WorkflowNodeFailure}（中文、已脱敏）。
 */
public interface WorkflowAgentInvoker {

    /**
     * 同步执行一次 Agent。
     *
     * @param agentId 目标 Agent 主键
     * @param message 已由 inputMapping 收口好的用户文本
     * @param userId  平台用户（计费 / 权限口径）
     * @param runId   工作流 run，用作试跑会话侧标识，便于排障串起来
     * @return 助手正文；实现方保证非空
     */
    String invoke(Long agentId, String message, long userId, long runId);
}

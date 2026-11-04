package com.agenthub.runtime.domain.model;

import java.util.List;

/**
 * 编排根下的「子专家计划待批准」视图。所属域：runtime。
 *
 * <p>用途：P&E 叶子在 require_plan_confirm=true 时，规划完成后把计划冒泡到根试跑会话，
 * 由 SSE {@code ma_child_plan_pending} 与刷新恢复接口共同下发。根侧同一时刻最多一条，
 * 因此 route_key 是定位「批的是哪个子」的稳定引用。
 *
 * <p>状态码：{@link #RUN_STATE_PAUSED} 表示根本轮已暂停等待子计划批准，
 * 与单 Agent P&E 的 PlanState.PENDING 语义一致；根会话库内 status 仍是 RUNNING，
 * 因为「等待批准」不是终态也不该改写 agent_run_session 的既有状态机。
 *
 * @param routeKey       该子在父编排里的路由键（可能为空串）
 * @param childAgentId   子 Agent 主键
 * @param childAgentName 子 Agent 展示名，中文提示用
 * @param planId         子计划标识，状态推进沿用
 * @param state          PENDING（待批准）；批准后由子自身推 EXECUTING
 * @param steps          子计划步骤，前端直接渲染列表
 * @param runState       根侧运行态码，固定 PAUSED_CHILD_APPROVAL
 */
public record ChildPlanPendingView(String routeKey, Long childAgentId, String childAgentName,
                                   String planId, String state, List<PlanStep> steps, String runState) {

    /** 根本轮暂停等待子计划批准（内存态；不写库内会话状态机） */
    public static final String RUN_STATE_PAUSED = "PAUSED_CHILD_APPROVAL";

    public ChildPlanPendingView {
        steps = steps == null ? List.of() : List.copyOf(steps);
        routeKey = routeKey == null ? "" : routeKey;
    }

    /**
     * @return 中文摘要，供 SSE / 面板标题使用
     */
    public String summary() {
        return "子专家「" + (childAgentName == null || childAgentName.isBlank() ? "子 Agent" : childAgentName)
                + "」计划待批准 · 共 " + steps.size() + " 步";
    }
}

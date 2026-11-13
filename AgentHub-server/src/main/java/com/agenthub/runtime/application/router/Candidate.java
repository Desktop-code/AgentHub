package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;

/**
 * 一个可执行的子候选：路由行 + 子 Agent 定义（或目标方案 / 目标工作流）。所属域：runtime。
 *
 * <p>由候选装载（{@code loadCandidates}）产出，参与判路由、固定模板、竞争选优等所有模板；
 * 抽到独立类型只是为了让模板执行器分担到同包协作类，字段与语义未变。
 *
 * <p><b>刀5 / 刀5B 三种目标</b>：
 * <ul>
 *   <li>普通候选：{@code agent} 非空，{@code maTarget} / {@code workflowTarget} 为空；</li>
 *   <li>目标为另一套编排方案：{@code maTarget} 非空，{@code agent} 是只读「方案外壳」；</li>
 *   <li>目标为已发布工作流：{@code workflowTarget} 非空，{@code agent} 是只读「工作流外壳」。</li>
 * </ul>
 * 两种外壳都只用于判路由提示词与时间线展示，主键为空、绝不落库；执行分支分别按
 * {@link #isMaTarget()} / {@link #isWorkflowTarget()} 委托出去。
 *
 * @param route          子路由行（含 route_key / 排序）
 * @param agent          子 Agent 定义（启用状态与运行模式已校验）；非 Agent 候选为只读外壳
 * @param maTarget       目标方案执行来源；其它候选为 null
 * @param workflowTarget 目标工作流执行来源；其它候选为 null
 */
public record Candidate(AgentRoute route, AgentDef agent, MaRunSpec maTarget, WorkflowRunSpec workflowTarget) {

    /**
     * 普通候选（子 Agent）。
     *
     * @param route 子路由行
     * @param agent 子 Agent 定义
     */
    public Candidate(AgentRoute route, AgentDef agent) {
        this(route, agent, null, null);
    }

    /**
     * 目标方案候选（刀5）。
     *
     * @param route    子路由行
     * @param agent    方案外壳
     * @param maTarget 目标方案执行来源
     */
    public Candidate(AgentRoute route, AgentDef agent, MaRunSpec maTarget) {
        this(route, agent, maTarget, null);
    }

    /**
     * @return true 表示本条候选的目标是另一套编排方案
     */
    public boolean isMaTarget() {
        return maTarget != null;
    }

    /**
     * @return true 表示本条候选的目标是已发布工作流
     */
    public boolean isWorkflowTarget() {
        return workflowTarget != null;
    }

    /**
     * @return true 表示不是子 Agent：判路由兜底、记忆注入、ReAct 装配等分支要跳过
     */
    public boolean delegatedTarget() {
        return isMaTarget() || isWorkflowTarget();
    }
}

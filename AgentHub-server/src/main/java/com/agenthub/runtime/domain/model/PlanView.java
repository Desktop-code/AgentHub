package com.agenthub.runtime.domain.model;

import java.util.List;

/**
 * 计划执行模式下的一份计划快照。所属域：runtime。
 * 同时用于：SSE 出站（前端渲染结构化步骤列表）、PLAN 事件正文（落库 JSON）、
 * 以及待批准计划的内存登记项。步骤顺序即执行顺序，禁止重排。
 *
 * <p>planId 是本次计划的稳定标识，随计划一起下发并落进 JSON。计划状态变化时
 * 会再写一条同 planId 的 PLAN 事件（不再重复 steps），前端按 planId 原位更新那张计划卡，
 * 避免时间线上堆出多张互相矛盾的「计划」。
 *
 * @param planId 本次计划标识；由规划步骤生成，同一轮内稳定
 * @param state  计划所处阶段
 * @param steps  按 seq 升序的步骤；状态更新事件里为空表示「沿用已有步骤」
 */
public record PlanView(String planId, PlanState state, List<PlanStep> steps) {

    public PlanView {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * @param state 新状态
     * @return 同一 planId、同一步骤换状态后的视图
     */
    public PlanView withState(PlanState state) {
        return new PlanView(planId, state, steps);
    }

    /**
     * @param state 新状态
     * @return 只带状态的视图，供「批准 / 执行完 / 失败 / 取消」这类状态更新使用
     */
    public PlanView stateOnly(PlanState state) {
        return new PlanView(planId, state, List.of());
    }

    /**
     * @return 步骤数与状态的中文说明，供时间线摘要
     */
    public String summary() {
        return "计划共 " + steps.size() + " 步 · " + stateText();
    }

    /**
     * @return 状态的中文说明，不含步数
     */
    public String stateText() {
        return switch (state) {
            case PENDING -> "待批准";
            case EXECUTING -> "执行中";
            case REJECTED -> "已取消";
            case DONE -> "已完成";
            case FAILED -> "执行失败";
        };
    }
}

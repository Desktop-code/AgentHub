package com.agenthub.runtime.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排根下「子专家计划待批准」响应。所属域：runtime。
 *
 * <p>与 SSE {@code ma_child_plan_pending} 载荷同构：前端一套解析即可同时处理实时帧与刷新恢复。
 * 与单 Agent 的 {@link PlanResp} 分开，避免根侧批准按钮误按 planId 去改根计划卡。
 */
@Data
public class ChildPlanPendingResp {

    /** 该子在父编排里的路由键（可能为空串） */
    private String routeKey;

    /** 子 Agent 主键 */
    private Long childAgentId;

    /** 子 Agent 展示名 */
    private String childAgentName;

    /** 子计划标识 */
    private String planId;

    /** 计划状态；当前只有 PENDING（待批准） */
    private String state;

    /** 根侧运行态：PAUSED_CHILD_APPROVAL（等待子计划批准） */
    private String runState;

    /** 中文摘要，例如「子专家「财务」计划待批准 · 共 3 步」 */
    private String summary;

    /** 子计划步骤；批准后执行进度走子自身 PLAN / STEP 事件 */
    private List<PlanResp.Step> steps = new ArrayList<>();
}

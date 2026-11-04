package com.agenthub.agent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * Agent 运行配置响应。所属域：agent。
 * 与库内 runtime_config_json 对应；字段均可有默认。
 * 三种模式共用一份响应，前端按 runMode 只渲染自己那组。
 */
@Data
public class AgentRuntimeConfigResp {

    /** 编排模板；五值之一，非法/空回显 ROUTER_PARALLEL */
    private String maTemplate;

    /** 上下文策略；ISOLATED（默认）/ PRIOR_SUMMARY_ONLY（仅串行模板）/ SHARED_SCRATCH（五值模板均可用） */
    private String maContextPolicy;

    /** 单意图时是否继承父历史；P0 默认 true */
    private Boolean maAllowParentHistoryWhenSingle;

    /** 允许多意图路由；ROUTER 用 */
    private Boolean multiIntentEnabled;

    /** CONCAT / SUMMARIZE；ROUTER 用 */
    private String answerMode;

    /** 每轮最多子 Agent 数；ROUTER 用 */
    private Integer maxRouteChildren;

    /** 规划后是否需要人工批准；PLAN_AND_EXECUTE 用 */
    private Boolean requirePlanConfirm;

    /** 计划步数上限；PLAN_AND_EXECUTE 用 */
    private Integer maxPlanSteps;

    /** 单步失败重试次数；PLAN_AND_EXECUTE 用 */
    private Integer maxStepRetries;

    /**
     * 编排根允许挂为候选子的运行模式；至少含 REACT，可能含 PLAN_AND_EXECUTE。
     * ROUTER 恒不出现在这里（子 ROUTER 被 allow_orchestrated 门闩禁止）。
     */
    private List<String> maChildRunModesAllowed;

    /** 评审环：最大评审轮次；默认 1，范围 1～3 */
    private Integer maCriticMaxRounds;

    /** 评审环：评审专家 agentId；未选为空 */
    private Long maCriticAgentId;
}

package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * Agent 运行配置响应。所属域：agent。
 * 与库内 runtime_config_json 对应；字段均可有默认。
 * 三种模式共用一份响应，前端按 runMode 只渲染自己那组。
 */
@Data
public class AgentRuntimeConfigResp {

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
}

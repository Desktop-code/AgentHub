package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent 运行配置请求。所属域：agent。
 * 可空；空字段由服务端按模式默认补齐后再入库。
 * 前三个字段服务 ROUTER，后三个服务 PLAN_AND_EXECUTE，按当前模式只读取对应那组。
 */
@Data
public class AgentRuntimeConfigReq {

    /** 允许多意图；新建 ROUTER 默认 true */
    private Boolean multiIntentEnabled;

    /** CONCAT / SUMMARIZE */
    @Size(max = 32, message = "最长 32")
    private String answerMode;

    /** 每轮最多子数；1～5 */
    @Min(value = 1, message = "至少 1")
    @Max(value = 5, message = "最多 5")
    private Integer maxRouteChildren;

    /** 计划执行：规划后是否需要人工批准才继续 */
    private Boolean requirePlanConfirm;

    /** 计划执行：计划步数上限；1～20 */
    @Min(value = 1, message = "至少 1")
    @Max(value = 20, message = "最多 20")
    private Integer maxPlanSteps;

    /** 计划执行：单步失败重试次数；0～3 */
    @Min(value = 0, message = "不能为负")
    @Max(value = 3, message = "最多 3")
    private Integer maxStepRetries;
}

package com.agenthub.agent.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent 运行配置响应。所属域：agent。
 * 与库内 runtime_config_json 对应；字段均可有默认。
 * 三种模式共用一份响应，前端按 runMode 只渲染自己那组。
 */
@Data
public class AgentRuntimeConfigResp {

    /** 编排模板；六值之一，非法/空回显 ROUTER_PARALLEL */
    private String maTemplate;

    /** 上下文策略；ISOLATED（默认）/ PRIOR_SUMMARY_ONLY（仅串行模板）/ SHARED_SCRATCH（五值模板均可用） */
    private String maContextPolicy;

    /** 单意图时是否继承父历史；P0 默认 true */
    private Boolean maAllowParentHistoryWhenSingle;

    /** 允许多意图路由；ROUTER 用 */
    private Boolean multiIntentEnabled;

    /** CONCAT / SUMMARIZE / VOTE；ROUTER 用（VOTE 仅路由并行 / 固定并行 / 路由后串行生效） */
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

    /** 判路由 / 汇总模型实例（model_instance.id）；虚拟根必填，绑根可空 */
    private Long maJudgeModelInstanceId;

    /** 判路由模型兼容别名（模型名）；只作回显，执行以 id 为准 */
    private String maJudgeModel;

    /**
     * ROUTER 提示词：钉住的已发布模板主键；未钉时为空。
     * <p>与 {@code maJudgePromptTemplateVersion} 成对生效；用途固定 ROUTER。
     */
    private Long maJudgePromptTemplateId;

    /** ROUTER 提示词：钉住的模板版本号 */
    private Integer maJudgePromptTemplateVersion;

    /**
     * ROUTER 提示词的 soft 段覆盖（seg_key → 文案）。
     * <p>未钉模板时恒为空 Map；锁定段不会出现在这里，服务端闸门已拒绝。
     */
    private Map<String, String> maJudgePromptOverride;

    /** ROUTER 提示词追加区（append-only）；未配置为空 */
    private String maJudgePromptExtra;

    /** 竞争选优（模板=COMPETE）的选优策略：JUDGE_PICK（默认）/ FIRST_SUCCESS */
    private String competeStrategy;
}

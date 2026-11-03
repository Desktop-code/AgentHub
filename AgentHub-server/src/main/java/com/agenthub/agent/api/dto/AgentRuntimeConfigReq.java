package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Agent 运行配置请求。所属域：agent。
 * 可空；空字段由服务端按模式默认补齐后再入库。
 * 前三个字段服务 ROUTER，后三个服务 PLAN_AND_EXECUTE，按当前模式只读取对应那组。
 */
@Data
public class AgentRuntimeConfigReq {

    /**
     * 编排模板；五值可持久：ROUTER_PARALLEL | FIXED_SEQUENCE | FIXED_PARALLEL | CRITIC_LOOP |
     * ROUTER_THEN_SEQUENCE。非法/空值由服务端回落 ROUTER_PARALLEL；显式选的模板不会被洗回路由并行。
     */
    @Size(max = 32, message = "最长 32")
    private String maTemplate;

    /**
     * 上下文策略；ISOLATED | PRIOR_SUMMARY_ONLY | SHARED_SCRATCH。
     * <p>PRIOR_SUMMARY_ONLY 只在串行模板下落库，其它模板收敛为 ISOLATED；
     * SHARED_SCRATCH 与模板无关，原样落库（草稿板本身不落库，仅本轮内存）。
     */
    @Size(max = 32, message = "最长 32")
    private String maContextPolicy;

    /** 单意图/单子时是否继承父历史；固定模板与判路由都按「参与子数为 1」读取 */
    private Boolean maAllowParentHistoryWhenSingle;

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

    /**
     * 编排根允许挂为候选子的运行模式；只认 REACT | PLAN_AND_EXECUTE。
     * <p>缺省 / 空数组回落 `["REACT"]`；服务端恒补 REACT 并剔除 ROUTER。
     */
    @Size(max = 2, message = "最多 2 项")
    private List<String> maChildRunModesAllowed;

    /**
     * 评审环：最大评审轮次；默认 1，服务端夹到 1～3。
     * <p>一轮 = 一次评审判定 +（若 REVISE）一次执行者回修。
     */
    @Min(value = 1, message = "至少 1 轮")
    @Max(value = 3, message = "最多 3 轮")
    private Integer maCriticMaxRounds;

    /** 评审环：评审专家 agentId；模板=CRITIC_LOOP 时必填，且不能与执行者相同 */
    private Long maCriticAgentId;
}

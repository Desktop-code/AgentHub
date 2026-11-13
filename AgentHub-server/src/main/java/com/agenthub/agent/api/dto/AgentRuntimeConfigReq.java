package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent 运行配置请求。所属域：agent。
 * 可空；空字段由服务端按模式默认补齐后再入库。
 * 前三个字段服务 ROUTER，后三个服务 PLAN_AND_EXECUTE，按当前模式只读取对应那组。
 */
@Data
public class AgentRuntimeConfigReq {

    /**
     * 编排模板；页面可持久六值：ROUTER_PARALLEL | FIXED_SEQUENCE | FIXED_PARALLEL | CRITIC_LOOP |
     * ROUTER_THEN_SEQUENCE | COMPETE。非法/空值由服务端回落 ROUTER_PARALLEL；显式选的模板不会被洗回路由并行。
     * <p>入口专用类型 {@code ENTRY_ROUTER} 不由页面新建或改名产生：它只出现在默认接待那一行，
     * 由迁移脚本写入，服务端 {@code MaDefPolicy} 拒绝把它当普通模板保存 / 互转。
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

    /** CONCAT / SUMMARIZE / VOTE；VOTE 仅路由并行 / 固定并行 / 路由后串行生效，其它模板归一为 CONCAT */
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

    /**
     * 判路由 / 终答汇总模型实例（model_instance.id）；虚拟根方案必填。
     * <p>空时绑根回落 ROUTER / CHAT 槽；虚拟根开跑会明确报「未配置判路由模型」。
     */
    private Long maJudgeModelInstanceId;

    /** 判路由模型兼容别名：数字按实例 id，名称按 model_id / 展示名匹配 */
    @Size(max = 128, message = "最长 128")
    private String maJudgeModel;

    /**
     * ROUTER 提示词：钉住的已发布模板主键。
     * <p>与 {@link #maJudgePromptTemplateVersion} 成对出现；只给一半时服务端按「未配置」处理。
     * 用途固定 ROUTER，由服务端写死，不从请求里取，避免页面把摘要 / 对话模板错绑到判路由上。
     */
    private Long maJudgePromptTemplateId;

    /** ROUTER 提示词：钉住的模板版本号 */
    private Integer maJudgePromptTemplateVersion;

    /**
     * ROUTER 提示词的 soft 段覆盖：seg_key → 文案。
     * <p>只能覆盖模板里 {@code mutable=1} 的段落；锁定段 / 未知段由服务端闸门拒绝，不在前端重复实现。
     */
    private Map<String, String> maJudgePromptOverride;

    /** ROUTER 提示词追加区（append-only）；最长 2000 字 */
    @Size(max = 2000, message = "Router 追加要求超过长度，最长 2000")
    private String maJudgePromptExtra;

    /**
     * 竞争选优（模板=COMPETE）的选优策略：JUDGE_PICK | FIRST_SUCCESS。
     * <p>缺省回落 JUDGE_PICK（评选模型选优）；非竞争模板也保存该值，便于切回时恢复。
     */
    @Size(max = 32, message = "最长 32")
    private String competeStrategy;
}

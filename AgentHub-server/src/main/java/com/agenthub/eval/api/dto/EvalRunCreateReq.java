package com.agenthub.eval.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 建跑入参。所属域：eval。
 * <p>字段对齐定稿 §5.4：{@code suite_id}、{@code suite_version?}、{@code agent_id}、
 * {@code judge_model_instance_id?}（控制台可选打分模型）、{@code config_override?}。
 * <p>非幂等：同参数连点会产生多条 PENDING（P0 不引入 client_token，见当刀开场【幂等】）。
 */
@Data
public class EvalRunCreateReq {

    /** 套件主键；必须是本租户且启用 */
    @NotNull(message = "不能为空")
    @Positive(message = "必须是正整数")
    @JsonProperty("suite_id")
    @JsonAlias("suiteId")
    private Long suiteId;

    /** 套件版本；空则取套件的 current_version */
    @Positive(message = "必须是正整数")
    @JsonProperty("suite_version")
    @JsonAlias("suiteVersion")
    private Integer suiteVersion;

    /** 被测 Agent；本刀只做非空校验，不校验 Agent 是否存在 */
    @NotNull(message = "不能为空")
    @Positive(message = "必须是正整数")
    @JsonProperty("agent_id")
    @JsonAlias("agentId")
    private Long agentId;

    /**
     * 本次跑批的打分模型实例 id（可选）。
     * <p>传 null / 不传 = 本 run 不打分（覆盖套件默认与全局 yml 的开启状态）；
     * 传值 = 启用 OpenJudge 软评分，并把它钉进 {@code openjudge.judge_model_instance_id}。
     * <p>也可经 {@code config_override.openjudge.judge_model_instance_id} 传入；顶层字段优先。
     */
    @Positive(message = "必须是正整数")
    @JsonProperty("judge_model_instance_id")
    @JsonAlias("judgeModelInstanceId")
    private Long judgeModelInstanceId;

    /** 本次覆盖配置（与套件 default_config 合并）；可空。目前只读取 openjudge.* */
    @JsonProperty("config_override")
    @JsonAlias("configOverride")
    private JsonNode configOverride;
}

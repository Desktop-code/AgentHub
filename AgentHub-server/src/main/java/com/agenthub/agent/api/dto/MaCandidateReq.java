package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 方案内单个候选的保存请求。所属域：agent。
 *
 * <p>route_key 可空：空则由服务端按子 Agent code（MA 目标按方案 code）生成，
 * 冲突回落 child_{id} / ma_{id}。
 *
 * <p><b>意图说明单一真源</b>：候选不保存独立的意图描述。AGENT 目标读
 * {@code agent_def.description}；MA 目标读 {@code ma_def.description}（刀5）；
 * WORKFLOW 目标读 {@code workflow_def.description}（刀5B）。因此本请求即使带了
 * {@code intentDesc} 也会被服务端忽略——要改文案请去对应管理页改简介 / 说明。
 *
 * <p><b>刀5 / 刀5B 目标类型</b>：{@code targetType} 缺省为 {@code AGENT}（兼容旧前端）；
 * 传 {@code MA} 时 {@code maDefId} 必填、{@code agentId} 忽略；
 * 传 {@code WORKFLOW} 时 {@code workflowDefId} 必填、{@code agentId} 忽略。
 */
@Data
public class MaCandidateReq {

    /** 目标类型缺省值：子 Agent */
    public static final String TARGET_AGENT = "AGENT";
    /** 目标类型：另一套编排方案 */
    public static final String TARGET_MA = "MA";
    /** 目标类型：已发布工作流 */
    public static final String TARGET_WORKFLOW = "WORKFLOW";

    /**
     * 候选目标类型：AGENT（缺省）/ MA / WORKFLOW。
     * 大小写不敏感，非法值按 AGENT 处理（与库默认值一致）。
     */
    @Size(max = 16, message = "最长 16")
    private String targetType;

    /** 候选子 Agent；targetType=AGENT 时必填，MA / WORKFLOW 目标忽略 */
    private Long agentId;

    /** 目标编排方案 ma_def.id；targetType=MA 时必填 */
    private Long maDefId;

    /** 目标工作流 workflow_def.id；targetType=WORKFLOW 时必填 */
    private Long workflowDefId;

    /** 可空；空则按子 Agent code（或方案 code）生成，同方案内唯一 */
    @Size(max = 64, message = "最长 64")
    private String routeKey;

    /**
     * 已废止：服务端一律用目标简介回填，不再接受调用方传入。
     *
     * <p>保留字段只为兼容旧前端（避免旧版本传值直接 400）；新前端不再提交。
     */
    @Deprecated
    @Size(max = 512, message = "最长 512")
    private String intentDesc;

    /** 可空；空则按同方案 max(sort_no)+10 生成 */
    @Min(value = 0, message = "不能小于 0")
    @Max(value = 10000, message = "不能大于 10000")
    private Integer sortNo;

    /** 可空，默认 1 启用 */
    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer enabled;

    /**
     * @return true 表示本条候选指向另一套编排方案
     */
    public boolean isMaTarget() {
        return TARGET_MA.equalsIgnoreCase(targetType);
    }

    /**
     * @return true 表示本条候选指向已发布工作流
     */
    public boolean isWorkflowTarget() {
        return TARGET_WORKFLOW.equalsIgnoreCase(targetType);
    }
}

package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 模型绑定行。所属域：agent。实例已删时名称字段为空，绑定行仍返回。
 */
@Data
public class ModelBindResp {

    private Long id;
    private Long agentId;
    private Long modelInstanceId;
    private String displayName;
    private String modelId;
    private String capability;
    private String purpose;
    /** 有效用途（purpose 覆盖或实例 capability） */
    private String effectivePurpose;
    /** 本槽内序号 1..3；无法归槽时为 null */
    private Integer slotRank;
    private Integer priority;
    private Integer status;
    /** 实例当前启停；实例已删时为空 */
    private Integer targetStatus;
    /**
     * 该槽非 CHAT 用途的 L3 提示词回显。
     * 用途无模板维度（VISION / ASR 等）时为 null，前端隐藏「提示词策略」。
     */
    private PromptBindingResp prompt;
}

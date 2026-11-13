package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 默认接待候选行：子 Agent / 另一套编排方案 / 已发布工作流。所属域：agent。
 *
 * <p>{@code targetType=AGENT} 时读 agent 字段；{@code targetType=MA} 时读 ma 字段
 * （主键在 maDefId，展示名在 maName）；{@code targetType=WORKFLOW}（刀5B）时读 workflow 字段
 * （主键在 workflowDefId，展示名在 workflowName）。前端线路 chip / 下拉统一用展示名，不暴露 route_key。
 *
 * <p>{@code receptionAssistant=true} 表示这是排序第一的候选：工作台默认接待先由它接住，
 * 判路由无合适专家时也回落到它。只有 AGENT 候选能当接待助手：人设只配在 Agent 的系统提示词上，
 * MA / 工作流候选即使排第一也不接管寒暄兜底（{@code receptionAssistant} 恒为 false）。
 *
 * <p>意图说明只读：Agent 取 {@code agent_def.description}，方案取 {@code ma_def.description}，
 * 工作流取 {@code workflow_def.description}；{@code intentDescMissing=true} 表示来源还没填，
 * 页面必须标出（此时它也无法被新加进路由池）。
 */
@Data
public class ReceptionCandidateResp {

    /** ma_candidate 主键，删除 / 启停 / 排序都用它 */
    private Long id;
    /** AGENT / MA / WORKFLOW；历史行为 AGENT */
    private String targetType;
    private Long agentId;
    private String agentName;
    private String agentCode;
    private String agentRunMode;
    /** MA 目标主键；targetType=MA 时非空 */
    private Long maDefId;
    private String maName;
    private String maCode;
    /** 方案模板码，只读展示 */
    private String maTemplate;
    /** 方案启用状态：1 启用 / 0 停用；停用后它的线路会失效 */
    private Integer maStatus;
    /** 工作流目标主键；targetType=WORKFLOW 时非空 */
    private Long workflowDefId;
    /** 工作流名称，线路 chip / 下拉展示 */
    private String workflowName;
    /** 工作流编码，只读展示 */
    private String workflowCode;
    /** 工作流当前是否仍有可用发布（HAS_RELEASE + 默认发布指针）；false 表示该线路已失效 */
    private Boolean workflowHasRelease;
    /** 只读回显：Agent 简介 / 方案说明 / 工作流说明 */
    private String intentDesc;
    /** true 表示来源说明为空，页面标红提示去对应管理页补 */
    private boolean intentDescMissing;
    /** 排序，越小越靠前 */
    private Integer sortNo;
    /** 1 启用 / 0 停用 */
    private Integer enabled;
    /** 是否排序第一的接待助手（只有 AGENT 候选可能为 true） */
    private boolean receptionAssistant;
}

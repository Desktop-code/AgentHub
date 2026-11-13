package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 方案候选行。所属域：agent。
 *
 * <p>比 ma_candidate 多带目标的展示信息（子 Agent 的 名称 / 编码 / 运行模式 / 启停，
 * 或目标方案的 名称 / 编码 / 说明，或目标工作流的 名称 / 编码 / 是否可运行），
 * 让前端抽屉与接待页不必为每个候选再查一次表。
 *
 * <p><b>三类目标</b>：{@code targetType=AGENT} 时 agentXxx 有值；{@code targetType=MA}（刀5）
 * 时 maXxx 有值；{@code targetType=WORKFLOW}（刀5B）时 workflowXxx 有值。
 * 意图说明按目标类型取对应单一真源（Agent 简介 / 方案说明 / 工作流说明）。
 */
@Data
public class MaCandidateResp {

    private Long id;
    /** AGENT / MA / WORKFLOW；旧数据为空时按 AGENT 展示 */
    private String targetType;
    private Long agentId;
    private String agentName;
    private String agentCode;
    /** REACT / PLAN_AND_EXECUTE */
    private String agentRunMode;
    /** 子 Agent 自身启停 */
    private Integer agentStatus;
    /** 子 Agent 是否允许被编排；0=禁止，前端提前标红 */
    private Integer agentAllowOrchestrated;
    /** 目标编排方案 ma_def.id；非 MA 目标为空 */
    private Long maDefId;
    /** 目标方案名称；非 MA 目标为空 */
    private String maName;
    /** 目标方案编码；非 MA 目标为空 */
    private String maCode;
    /** 目标方案模板；非 MA 目标为空 */
    private String maTemplate;
    /** 目标方案自身启停 */
    private Integer maStatus;
    /** 目标工作流 workflow_def.id；非 WORKFLOW 目标为空 */
    private Long workflowDefId;
    /** 目标工作流名称；非 WORKFLOW 目标为空 */
    private String workflowName;
    /** 目标工作流编码；非 WORKFLOW 目标为空 */
    private String workflowCode;
    /** 目标工作流当前是否仍有可用发布（HAS_RELEASE + 默认发布指针）；不可运行时仍留在池里但标红 */
    private Boolean workflowHasRelease;
    private String routeKey;
    /**
     * 意图说明只读回显：AGENT 目标来自子 Agent 简介（agent_def.description），
     * MA 目标来自方案说明（ma_def.description），WORKFLOW 目标来自工作流说明
     * （workflow_def.description）；不在候选上单独维护。
     */
    private String intentDesc;
    /** true 表示目标还没填简介 / 说明：页面必须标出，不能静默用空描述判路由 */
    private boolean intentDescMissing;
    private Integer sortNo;
    private Integer enabled;
}

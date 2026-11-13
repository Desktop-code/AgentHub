package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 向默认接待添加一个路由候选：子 Agent / 另一套编排方案 / 已发布工作流。所属域：agent。
 *
 * <p>三选一：{@code agentId}（子 Agent）、{@code maDefId}（另一套多智能体方案，刀5）、
 * {@code workflowDefId}（已发布工作流，刀5B）。优先级为 agentId → maDefId → workflowDefId；
 * 都不给由服务端返回 400。
 *
 * <p>意图说明不再由本请求决定：Agent 取 {@code agent_def.description}，
 * 方案取 {@code ma_def.description}，工作流取 {@code workflow_def.description}，
 * 一律服务端回填，为空直接拒绝。{@code intentDesc} 字段仅为旧前端兼容保留，会被忽略。
 */
@Data
public class ReceptionCandidateAddReq {

    /** 子 Agent 主键；与 maDefId / workflowDefId 三选一 */
    private Long agentId;

    /** 另一套多智能体方案主键；与 agentId / workflowDefId 三选一 */
    private Long maDefId;

    /** 已发布工作流主键（workflow_def.id）；与 agentId / maDefId 三选一 */
    private Long workflowDefId;

    /**
     * 已废止：意图说明单一真源是 Agent 简介 / 方案说明 / 工作流说明。
     *
     * <p>保留只为旧前端不因多传字段而 400；新前端不再提交。
     */
    @Deprecated
    @Size(max = 512, message = "意图说明最长 512")
    private String intentDesc;
}

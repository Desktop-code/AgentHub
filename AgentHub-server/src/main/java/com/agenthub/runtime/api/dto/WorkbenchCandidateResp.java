package com.agenthub.runtime.api.dto;

import com.agenthub.agent.api.dto.AgentAvatarConfigDto;
import lombok.Data;

/**
 * 工作台页面展示用的候选（子 Agent / 另一套编排方案 / 已发布工作流）。所属域：runtime。
 *
 * <p>只给展示与「谁接住了这条线」的说明，不暴露提示词、模型 Key、工具配置或流程画布。
 * {@code targetType=MA} 时 agentId 为空、maDefId 有值，展示名在 agentName（目标方案名）；
 * {@code targetType=WORKFLOW}（刀5B）时 workflowDefId 有值，展示名仍在 agentName（工作流名称）。
 */
@Data
public class WorkbenchCandidateResp {

    /** AGENT / MA / WORKFLOW */
    private String targetType;
    private Long agentId;
    /** targetType=MA 时的目标方案主键 */
    private Long maDefId;
    /** targetType=WORKFLOW 时的目标工作流主键 */
    private Long workflowDefId;
    private String agentName;
    /** Agent 形象配置；MA/工作流候选为空 */
    private AgentAvatarConfigDto avatarConfig;
    /** 意图说明：Agent 简介 / 方案说明 / 工作流说明（均为只读单一真源） */
    private String intentDesc;
    private String routeKey;
    private Integer sortNo;
}

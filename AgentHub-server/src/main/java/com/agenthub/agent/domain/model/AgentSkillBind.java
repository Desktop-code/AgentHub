package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 与 Skill 的绑定。所属域：agent。对应 agent_skill_bind，只存 skill_def.id。
 */
@Data
public class AgentSkillBind {

    private Long id;
    private Long agentId;
    private Long skillId;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

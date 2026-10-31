package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 与工具项的绑定。所属域：agent。对应 agent_tool_bind。
 */
@Data
public class AgentToolBind {

    private Long id;
    private Long agentId;
    private Long toolItemId;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Router 的一层子路由。所属域：agent。对应 agent_route。
 * 父节点必须是 ROUTER，子节点必须是 REACT。
 */
@Data
public class AgentRoute {

    private Long id;
    private Long parentAgentId;
    private Long childAgentId;
    private String routeKey;
    private String intent;
    private Integer sortOrder;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

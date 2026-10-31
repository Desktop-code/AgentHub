package com.agenthub.agent.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent_route。所属域：agent。一层子路由，parent 与 child 都指向 agent_def.id。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_route")
public class AgentRouteEntity extends AgentAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentAgentId;
    private Long childAgentId;
    private String routeKey;
    private String intent;
    private Integer sortOrder;
    private Integer status;

    @TableLogic
    private Integer deleted;
}

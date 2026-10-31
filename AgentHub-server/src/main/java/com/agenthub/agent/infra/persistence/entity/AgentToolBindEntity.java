package com.agenthub.agent.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent_tool_bind。所属域：agent。无物理外键，tool_item_id 由应用层校验。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_tool_bind")
public class AgentToolBindEntity extends AgentAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long toolItemId;
    private Integer status;

    @TableLogic
    private Integer deleted;
}

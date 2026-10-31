package com.agenthub.agent.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent_skill_bind。所属域：agent。skill_id 指向 skill_def.id，不建外键。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_skill_bind")
public class AgentSkillBindEntity extends AgentAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long skillId;
    private Integer status;

    @TableLogic
    private Integer deleted;
}

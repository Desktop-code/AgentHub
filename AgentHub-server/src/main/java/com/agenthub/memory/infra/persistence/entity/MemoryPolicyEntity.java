package com.agenthub.memory.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * memory_agent_policy。所属域：memory。列与增量 SQL 一致，不在代码里建表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("memory_agent_policy")
public class MemoryPolicyEntity extends MemoryAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Integer enableShort;
    private Integer enableLong;
    private Integer maxShortItems;
    private Integer maxLongItems;
    private Integer ctxMaxTurns;
    private Integer ctxMaxChars;
    private Integer ctxIdleTtlSec;
    private Integer shortDefaultTtlSec;
    private Long summaryModelInstanceId;
    private Integer enableLongExtract;
    private Long longExtractModelInstanceId;
    private Integer longExtractOnSessionEnd;
    private Integer longExtractOnExplicit;
    private Integer status;
    private String remark;

    @TableLogic
    private Integer deleted;
}

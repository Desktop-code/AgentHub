package com.agenthub.agent.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ma_candidate。所属域：agent 的 infra。
 *
 * <p>候选是方案从属子资源：保存方案时全量替换，不做逻辑删除。
 * Entity 不出本包。
 */
@Data
@TableName("ma_candidate")
public class MaCandidateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long maId;
    private Long agentId;
    /** 候选目标类型：AGENT / MA / WORKFLOW；存量行为 AGENT，列 NOT NULL */
    private String targetType;
    private Long maDefId;
    /** target_type=WORKFLOW 时指向 workflow_def.id */
    private Long workflowDefId;
    private String routeKey;
    private String intentDesc;
    private Integer sortNo;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

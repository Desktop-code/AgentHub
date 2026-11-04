package com.agenthub.workflow.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * workflow_human_task。所属域：workflow 的 infra。
 * <p>node_run 唯一：一个 HUMAN 节点在一 run 内只挂一条待办。
 */
@Data
@TableName("workflow_human_task")
public class WorkflowHumanTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long runId;
    private Long nodeRunId;
    private String nodeCode;
    private String status;
    private String assigneeRule;
    private String timeoutPolicy;
    private String formJson;
    private String resultJson;
    private LocalDateTime expireTime;
    private Long decidedBy;
    private LocalDateTime decidedTime;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

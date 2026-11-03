package com.agenthub.workflow.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * workflow_release。所属域：workflow 的 infra。
 * <p>不可变表：本工程只 insert / select，不提供 update 语句。
 */
@Data
@TableName("workflow_release")
public class WorkflowReleaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workflowId;
    private Integer version;
    private String graphJson;
    private String fingerprint;
    private String publishNote;
    private Long publishedBy;
    private LocalDateTime publishTime;
    private Long createdBy;
    private LocalDateTime createTime;
}

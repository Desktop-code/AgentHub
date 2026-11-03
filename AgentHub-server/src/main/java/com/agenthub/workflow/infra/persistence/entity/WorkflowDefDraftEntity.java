package com.agenthub.workflow.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * workflow_def_draft。所属域：workflow 的 infra。
 * <p>主键就是 workflowId（1:1）；graph_json 是 JSON 列，读写按字符串处理。
 */
@Data
@TableName("workflow_def_draft")
public class WorkflowDefDraftEntity {

    @TableId(type = IdType.INPUT)
    private Long workflowId;
    private String graphJson;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

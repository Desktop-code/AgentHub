package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流草稿。所属域：workflow 的 domain。对应表 workflow_def_draft。
 * <p>与定义 1:1；graphJson 是原样落库的图 JSON（nodes / edges / viewport），
 * updateTime 作为乐观锁口径（PUT 草稿时可带期望值）。
 */
@Data
public class WorkflowDraft {

    /** 对应 workflow_def.id */
    private Long workflowId;
    /** 图 JSON 原文 */
    private String graphJson;
    /** 创建人 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新人 */
    private Long updatedBy;
    /** 更新时间（乐观锁口径） */
    private LocalDateTime updateTime;
}

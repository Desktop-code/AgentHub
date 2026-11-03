package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流定义。所属域：workflow 的 domain。对应表 workflow_def。
 * <p>约束：code 租户内唯一且创建后冻结；status 是生命周期（DRAFT_ONLY / HAS_RELEASE），
 * defaultReleaseId 是默认发布指针，发布时指向最新 release。
 */
@Data
public class WorkflowDef {

    /** 主键 */
    private Long id;
    /** 租户 id */
    private Long tenantId;
    /** 租户内逻辑编码，创建后不可改 */
    private String code;
    /** 中文名 */
    private String name;
    /** 说明 */
    private String description;
    /** DRAFT_ONLY / HAS_RELEASE */
    private String status;
    /** 默认发布指针；空=尚未发布 */
    private Long defaultReleaseId;
    /** 创建人 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新人 */
    private Long updatedBy;
    /** 更新时间 */
    private LocalDateTime updateTime;
}

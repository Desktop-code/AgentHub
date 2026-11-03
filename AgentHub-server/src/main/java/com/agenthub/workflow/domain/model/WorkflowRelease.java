package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流发布版本。所属域：workflow 的 domain。对应表 workflow_release。
 * <p>不可变：只追加，不更新历史行的 graph_json / fingerprint / version。
 * 租户隔离经 {@link WorkflowDef#getTenantId()} 完成，本模型不带 tenantId。
 */
@Data
public class WorkflowRelease {

    /** 主键 */
    private Long id;
    /** workflow_def.id */
    private Long workflowId;
    /** 版本号，从 1 递增 */
    private Integer version;
    /** 发布时的不可变图 JSON */
    private String graphJson;
    /** 依赖指纹：agentId / toolId 排序后的 SHA-256 */
    private String fingerprint;
    /** 发布说明 */
    private String publishNote;
    /** 发布人 */
    private Long publishedBy;
    /** 发布时间 */
    private LocalDateTime publishTime;
    /** 创建人（等同发布人） */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
}

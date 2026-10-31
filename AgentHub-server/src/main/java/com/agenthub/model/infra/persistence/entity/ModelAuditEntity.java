package com.agenthub.model.infra.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型表审计列。所属域：model 的 infra。
 * 不继承 admin 的 AuditableEntity，避免模型持久化依赖管理域。
 * 列名对齐定稿：create_time / update_time（非 created_at）。
 */
@Data
public abstract class ModelAuditEntity {

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

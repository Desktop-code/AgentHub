package com.agenthub.admin.infra.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计列。所属域：admin 的 infra。只给本包 sys_* 实体复用，不作为业务继承体系。
 * 列名对齐定稿：create_time / update_time（非 created_at）。
 */
@Data
public abstract class AuditableEntity {

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

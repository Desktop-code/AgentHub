package com.agenthub.billing.infra.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费表审计列。所属域：billing 的 infra。
 * 不继承别的域的审计基类，避免计费持久化反向依赖。
 * 列名对齐定稿：create_time / update_time。
 */
@Data
public abstract class BillingAuditEntity {

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

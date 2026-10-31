package com.agenthub.memory.infra.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记忆表审计列。所属域：memory 的 infra。
 * 列名是 create_time / update_time，不使用 created_at。
 */
@Data
public abstract class MemoryAuditEntity {

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

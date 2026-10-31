package com.agenthub.tool.infra.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具表审计列。所属域：tool 的 infra。
 * 不继承 model 或 admin 的审计基类，避免工具持久化依赖别的业务域。
 * 列名对齐定稿：create_time / update_time。
 */
@Data
public abstract class ToolAuditEntity {

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

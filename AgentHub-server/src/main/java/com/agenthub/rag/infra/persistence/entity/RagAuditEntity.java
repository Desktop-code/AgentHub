package com.agenthub.rag.infra.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库表审计列。所属域：rag 的 infra。
 * 不继承其它域的审计基类，避免 rag 持久化依赖 admin / tool / skill。
 * 列名对齐定稿：create_time / update_time。
 */
@Data
public abstract class RagAuditEntity {

    /** 创建人用户 id */
    private Long createdBy;
    /** 创建时间（库列 create_time） */
    private LocalDateTime createTime;
    /** 更新人用户 id */
    private Long updatedBy;
    /** 更新时间（库列 update_time） */
    private LocalDateTime updateTime;
}

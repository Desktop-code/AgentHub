package com.agenthub.eval.infra.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评测表审计列。所属域：eval 的 infra。
 * 不继承别的业务域审计基类，避免 eval 持久化反向依赖 admin / skill / rag。
 * 列名对齐 schema-delta-eval.sql：create_by / update_by / create_time / update_time。
 */
@Data
public abstract class EvalAuditEntity {

    /** 创建人用户 id（库列 create_by） */
    private Long createBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新人用户 id（库列 update_by） */
    private Long updateBy;
    /** 更新时间 */
    private LocalDateTime updateTime;
}

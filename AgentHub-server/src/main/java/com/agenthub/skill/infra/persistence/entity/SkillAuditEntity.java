package com.agenthub.skill.infra.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 表审计列。所属域：skill 的 infra。
 * 不继承 admin 或 tool 的审计基类，避免 Skill 持久化依赖别的业务域。
 * 列名对齐定稿：create_time / update_time。
 */
@Data
public abstract class SkillAuditEntity {

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

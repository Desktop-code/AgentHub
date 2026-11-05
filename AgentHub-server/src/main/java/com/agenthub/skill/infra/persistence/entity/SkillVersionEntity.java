package com.agenthub.skill.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * skill_version。所属域：skill 的 infra。列名与 sql/temp/schema-delta-skill-version.sql 一致。
 *
 * <p>active_flag 是 DB 生成列（生效位唯一索引兜底），Java 侧只读、永不写入；
 * 这里不声明该字段，MyBatis-Plus 插入/更新自然绕开它。
 * 正文不进 toString，避免日志打出整篇 Markdown。
 */
@Data
@TableName("skill_version")
public class SkillVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skillId;
    private Integer versionNo;
    @ToString.Exclude
    private String contentMd;
    private String description;
    /** 0 草稿 / 1 已发布 */
    private Integer publishStatus;
    /** 1 生效 */
    private Integer isActive;
    private LocalDateTime publishedAt;
    private Long publishedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

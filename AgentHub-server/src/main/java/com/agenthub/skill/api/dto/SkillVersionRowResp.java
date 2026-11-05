package com.agenthub.skill.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 版本列表行。所属域：skill。故意不带 contentMd，版本列表不传大字段。
 */
@Data
public class SkillVersionRowResp {

    private Long id;
    private Long skillId;
    /** 版本号，从 1 递增；同 Skill 唯一 */
    private Integer versionNo;
    /** 该版本简介（不含正文） */
    private String description;
    /** 0 草稿 / 1 已发布 */
    private Integer publishStatus;
    /** 1 表示当前生效 */
    private Integer isActive;
    private LocalDateTime publishedAt;
    private Long publishedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

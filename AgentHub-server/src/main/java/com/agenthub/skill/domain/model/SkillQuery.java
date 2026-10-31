package com.agenthub.skill.domain.model;

import lombok.Data;

/**
 * Skill 列表条件。所属域：skill。分页字段与 PageQuery 一致，上限在仓储里再夹一次。
 */
@Data
public class SkillQuery {

    private long current = 1;
    private long size = 20;
    private String keyword;
    private Integer status;
}

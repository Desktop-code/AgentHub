package com.agenthub.skill.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Skill 卡片分页。所属域：skill。keyword、status 可空；本接口不返回正文。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillPageReq extends PageQuery {

    private String keyword;
    private Integer status;
}

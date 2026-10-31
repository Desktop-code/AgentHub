package com.agenthub.skill.api.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Skill 详情。所属域：skill。在卡片字段上补 Markdown 正文。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillDetailResp extends SkillCardResp {

    private String contentMd;
}

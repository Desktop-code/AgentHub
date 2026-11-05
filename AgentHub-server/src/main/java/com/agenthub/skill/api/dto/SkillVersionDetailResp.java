package com.agenthub.skill.api.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Skill 版本详情。所属域：skill。在列表行上补 Markdown 正文。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillVersionDetailResp extends SkillVersionRowResp {

    private String contentMd;
}

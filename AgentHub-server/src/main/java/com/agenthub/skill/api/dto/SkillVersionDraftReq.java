package com.agenthub.skill.api.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 基于某已发布版本新建草稿。所属域：skill。
 *
 * <p>fromVersionId 为空时开一条空白草稿；非空时复制该版本正文与简介作为草稿初稿。
 * 该版本必须属于同一 Skill。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillVersionDraftReq extends SkillVersionSaveReq {

    /** 复制来源版本 id，可空；来源必须已发布且属于当前 Skill */
    private Long fromVersionId;
}

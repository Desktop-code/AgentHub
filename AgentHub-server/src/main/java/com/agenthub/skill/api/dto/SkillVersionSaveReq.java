package com.agenthub.skill.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存 Skill 草稿。所属域：skill。
 *
 * <p>只改版本行的简介与正文；名称/标题/触发词/启停属于 skill_def，不走这里。
 * 已发布版本只读，保存会返回业务错误。
 */
@Data
public class SkillVersionSaveReq {

    /** 该版本简介：会进 Prompt 的 Skill 目录；空串按 null 入库 */
    @Size(max = 1000, message = "最长 1000")
    private String description;

    /** Markdown 正文；应用层再按字符数拦一次（见 SkillChecks.CONTENT_MAX） */
    private String contentMd;
}

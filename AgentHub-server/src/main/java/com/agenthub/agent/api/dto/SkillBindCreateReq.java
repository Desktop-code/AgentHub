package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新增 Skill 绑定。所属域：agent。只引用 skill_def.id，不复制正文。
 */
@Data
public class SkillBindCreateReq {

    @NotNull(message = "不能为空")
    private Long skillId;

    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer status;
}

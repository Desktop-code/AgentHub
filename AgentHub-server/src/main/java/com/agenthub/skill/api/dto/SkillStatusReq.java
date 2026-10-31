package com.agenthub.skill.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Skill 启停。所属域：skill。1 正常，0 停用。
 */
@Data
public class SkillStatusReq {

    @NotNull(message = "不能为空")
    private Integer status;
}

package com.agenthub.skill.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建 Skill。所属域：skill。正文可空，启停默认正常。
 * triggerKeywords 用 Tag 数组提交（前端 el-select multiple），落库前由 SkillAssembler join 成逗号串。
 */
@Data
public class SkillCreateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "最长 128")
    private String title;

    /** 简介：供大模型判断何时加载此技能，会进 Prompt 的 Skill 目录 */
    @Size(max = 1000, message = "最长 1000")
    private String description;

    /** 触发关键词 Tag 数组；命中任一词则强制注入正文。个数与单项长度在应用层再收一次 */
    private List<String> triggerKeywords;

    /** 应用层按字符数再拦一次，避免单次请求接近 MEDIUMTEXT 上限 */
    @Size(max = 1_000_000, message = "正文最长 1000000 个字符")
    private String contentMd;

    @Size(max = 32, message = "最长 32")
    private String version;

    /** 备注：仅内部人员可见，永不注入模型 */
    @Size(max = 500, message = "最长 500")
    private String remark;

    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer status;
}

package com.agenthub.skill.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新 Skill 元数据和正文。所属域：skill。启停不走这个入参。
 * triggerKeywords 用 Tag 数组提交；不传（null）与传空数组等价，都会清空关键词。
 */
@Data
public class SkillUpdateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    @NotBlank(message = "不能为空")
    @Size(max = 128, message = "最长 128")
    private String title;

    /** 简介：供大模型判断何时加载此技能，会进 Prompt 的 Skill 目录 */
    @Size(max = 1000, message = "最长 1000")
    private String description;

    /** 触发关键词 Tag 数组；命中任一词则强制注入正文 */
    private List<String> triggerKeywords;

    /** 与新建共用 100 万字符上限，库列仍是 MEDIUMTEXT */
    @Size(max = 1_000_000, message = "正文最长 1000000 个字符")
    private String contentMd;

    @Size(max = 32, message = "最长 32")
    private String version;

    /** 备注：仅内部人员可见，永不注入模型 */
    @Size(max = 500, message = "最长 500")
    private String remark;
}

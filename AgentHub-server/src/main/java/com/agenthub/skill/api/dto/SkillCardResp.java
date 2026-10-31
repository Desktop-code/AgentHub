package com.agenthub.skill.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill 卡片。所属域：skill。故意没有 contentMd，列表不回传正文。
 * triggerKeywords 已由 Assembler 从库内逗号串拆成数组，前端直接当 Tag 用（卡片只展示前 2 个）。
 * remark 仅内部人员可见，永不注入模型。
 */
@Data
public class SkillCardResp {

    private Long id;
    private String name;
    private String title;
    /** 简介：供大模型判断何时加载此技能 */
    private String description;
    /** 触发关键词 Tag 数组；空数组表示没有关键词，不会硬触发 */
    private List<String> triggerKeywords;
    private String version;
    private Integer status;
    /** 备注：仅内部人员可见，永不注入模型 */
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

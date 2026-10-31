package com.agenthub.skill.domain.model;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Skill 定义。所属域：skill。对应表 skill_def，version 只是字符串。
 * contentMd 可能很大，禁止进入操作日志和 toString。
 * 字段职责写死：
 * <ul>
 *   <li>description：简介，供大模型判断何时加载此技能（会进 Prompt 的 Skill 目录）</li>
 *   <li>remark：备注，仅内部人员可见，永不注入模型</li>
 *   <li>triggerKeywords：库内逗号串；用户输入命中任一词则强制注入 contentMd</li>
 * </ul>
 */
@Data
public class SkillDef {

    private Long id;
    private String name;
    private String title;
    /** 简介：供大模型判断何时加载此技能，会进 Prompt 的 Skill 目录 */
    private String description;
    /** 触发关键词，库内英文逗号分隔；命中即由系统强制注入正文 */
    private String triggerKeywords;
    /** 正文不进日志 */
    @ToString.Exclude
    private String contentMd;
    private String version;
    private Integer status;
    /** 备注：仅内部人员可见，永不注入模型 */
    private String remark;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

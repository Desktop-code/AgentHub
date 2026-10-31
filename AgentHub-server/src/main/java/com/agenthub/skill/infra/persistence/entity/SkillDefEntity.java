package com.agenthub.skill.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * skill_def。所属域：skill。列名与 sql/schema-delta-skill.sql + schema-delta-skill-trigger.sql 一致。
 * 正文不进 toString，避免日志打出整篇 Markdown。
 * triggerKeywords 是库内逗号串，拆分与合并只在 SkillTriggerMatcher / SkillAssembler，Entity 不做转换。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill_def")
public class SkillDefEntity extends SkillAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String title;
    /** 简介：供大模型判断何时加载此技能，会进 Prompt 的 Skill 目录 */
    private String description;
    /** 触发关键词，英文逗号分隔；用户输入命中任一词则强制注入正文 */
    private String triggerKeywords;
    @ToString.Exclude
    private String contentMd;
    private String version;
    private Integer status;
    /** 备注：仅内部人员可见，永不注入模型 */
    private String remark;

    @TableLogic
    private Integer deleted;
}

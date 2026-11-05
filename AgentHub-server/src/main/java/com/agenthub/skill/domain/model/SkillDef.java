package com.agenthub.skill.domain.model;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Skill 定义。所属域：skill。对应表 skill_def。
 *
 * <p>真版本口径（P1）：正文以 skill_version 为准，本模型的 contentMd 是
 * <b>只读派生字段</b>——由仓储从当前生效已发布版本读出，绝不回写 skill_def.content_md。
 * version 同为冗余展示（生效版本号字符串），升版只走「发布」，禁止手改冒充历史。
 * contentMd 可能很大，禁止进入操作日志和 toString。
 * 字段职责写死：
 * <ul>
 *   <li>description：简介，供大模型判断何时加载此技能（会进 Prompt 的 Skill 目录）；发布时由生效版镜像</li>
 *   <li>remark：备注，仅内部人员可见，永不注入模型</li>
 *   <li>triggerKeywords：库内逗号串；用户输入命中任一词则强制注入生效版正文</li>
 * </ul>
 */
@Data
public class SkillDef {

    private Long id;
    private String name;
    private String title;
    /** 简介：供大模型判断何时加载此技能，会进 Prompt 的 Skill 目录；生效版镜像 */
    private String description;
    /** 触发关键词，库内英文逗号分隔；命中即由系统强制注入正文 */
    private String triggerKeywords;
    /**
     * 正文：只读派生字段，来自当前生效已发布版本的 content_md，不落 skill_def.content_md。
     * 不写回、不留日志。
     */
    @ToString.Exclude
    private String contentMd;
    /** 冗余展示：当前生效版本号字符串；未发布时为空串 */
    private String version;
    private Integer status;
    /** 备注：仅内部人员可见，永不注入模型 */
    private String remark;
    /** 派生：当前生效版本号；无发布版时为 null（非库列） */
    private Integer activeVersionNo;
    /** 派生：是否存在草稿（非库列） */
    private Boolean hasDraft;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

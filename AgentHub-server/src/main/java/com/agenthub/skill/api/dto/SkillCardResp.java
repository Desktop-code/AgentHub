package com.agenthub.skill.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill 卡片。所属域：skill。故意没有 contentMd，列表不回传正文。
 * triggerKeywords 已由 Assembler 从库内逗号串拆成数组，前端直接当 Tag 用（卡片只展示前 2 个）。
 * remark 仅内部人员可见，永不注入模型。
 *
 * <p>版本口径：activeVersionNo 是当前生效的已发布版本号（未发布时为 null），
 * hasDraft 表示是否存在草稿；version 只是字符串镜像，前端一律优先读 activeVersionNo。
 */
@Data
public class SkillCardResp {

    private Long id;
    private String name;
    private String title;
    /** 简介：供大模型判断何时加载此技能；取自生效已发布版本 */
    private String description;
    /** 触发关键词 Tag 数组；空数组表示没有关键词，不会硬触发 */
    private List<String> triggerKeywords;
    /** 生效版本号字符串镜像；未发布时为空串（展示请用 activeVersionNo） */
    private String version;
    /** 当前生效的已发布版本号；没有发布过则为 null */
    private Integer activeVersionNo;
    /** 是否存在草稿 */
    private Boolean hasDraft;
    private Integer status;
    /** 备注：仅内部人员可见，永不注入模型 */
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

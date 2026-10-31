package com.agenthub.prompt.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 左栏 L1 摘要。所属域：prompt。
 * 只出卡片需要的版本/状态，不带正文，避免目录接口把 MEDIUMTEXT 一起拖回来。
 */
@Data
public class PromptCatalogConstraintResp {

    private Long id;
    private String locale;
    private Integer version;
    /** 0 草稿 1 已发布 */
    private Integer status;
    /** 1 表示该 locale 当前生效 */
    private Integer isActive;
    private LocalDateTime updateTime;
}

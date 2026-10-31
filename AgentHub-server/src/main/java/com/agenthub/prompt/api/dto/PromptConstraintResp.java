package com.agenthub.prompt.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * L1 约束出参。所属域：prompt。时间字段 *Time。
 */
@Data
public class PromptConstraintResp {

    private Long id;
    private String locale;
    private String content;
    private Integer version;
    private Integer status;
    private Integer isActive;
    private Long createBy;
    private Long updateBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

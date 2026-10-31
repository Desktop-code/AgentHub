package com.agenthub.prompt.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * L2 段落出参。所属域：prompt。
 */
@Data
public class PromptSegmentResp {

    private Long id;
    private Long templateId;
    private String segKey;
    private String displayName;
    private Integer sortNo;
    private Integer mutable;
    private Integer required;
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

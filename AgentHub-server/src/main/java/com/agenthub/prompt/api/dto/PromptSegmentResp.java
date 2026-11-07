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
    /** true 表示该段正文由平台锁定（如记忆抽取的契约/输出格式），页面不可改 */
    private Boolean contentLocked;
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

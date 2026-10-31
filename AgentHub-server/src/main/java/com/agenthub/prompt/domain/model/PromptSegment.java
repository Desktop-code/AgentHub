package com.agenthub.prompt.domain.model;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * L2 模板段落。所属域：prompt。
 * display_name 存库，供后续 L3 表单 Label；本刀只做管理端读写。
 */
@Data
public class PromptSegment {

    private Long id;
    private Long templateId;
    private String segKey;
    private String displayName;
    private Integer sortNo;
    /** 0=core 锁定；1=soft 可被 L3 覆盖 */
    private Integer mutable;
    /** 1=缺段时组装应失败（Assembler 另刀） */
    private Integer required;
    @ToString.Exclude
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

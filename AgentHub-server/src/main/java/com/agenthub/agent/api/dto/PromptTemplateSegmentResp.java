package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * L3 表单段落。所属域：agent。
 * {@code mutable=0} 为 core（只读展示），{@code mutable=1} 为 soft（可覆盖）。
 */
@Data
public class PromptTemplateSegmentResp {

    /** 段键；soft 段即 override 的提交键 */
    private String segKey;
    /** 表单 Label；禁只展示英文 segKey */
    private String displayName;
    private Integer sortNo;
    private Integer mutable;
    private Integer required;
    /** 段落正文；core 段只读展示 */
    private String content;
}

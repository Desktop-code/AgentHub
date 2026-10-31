package com.agenthub.prompt.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * L2 段落入参。所属域：prompt。
 */
@Data
public class PromptSegmentReq {

    @NotBlank(message = "segKey 不能为空")
    private String segKey;
    @NotBlank(message = "displayName 不能为空")
    private String displayName;
    private Integer sortNo;
    /** 0 锁定 1 可覆盖；默认 0 */
    private Integer mutable;
    /** 1 必选；默认 1 */
    private Integer required;
    @NotBlank(message = "段落正文不能为空")
    private String content;
}

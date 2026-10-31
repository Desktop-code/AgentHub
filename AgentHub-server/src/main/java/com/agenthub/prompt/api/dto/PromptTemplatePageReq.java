package com.agenthub.prompt.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * L2 分页条件。所属域：prompt。
 */
@Data
public class PromptTemplatePageReq {

    @Min(1)
    private long current = 1;
    @Min(1)
    @Max(100)
    private long size = 10;
    private String keyword;
    /** 业务键精确匹配；中栏「该对象全部版本」用它，不与模糊关键词混用 */
    private String templateKey;
    private String purpose;
    private String locale;
    private Integer status;
    private Integer isActive;
}

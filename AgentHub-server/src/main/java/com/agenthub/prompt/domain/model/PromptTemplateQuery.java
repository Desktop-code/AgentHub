package com.agenthub.prompt.domain.model;

import lombok.Data;

/**
 * L2 分页查询条件。所属域：prompt。
 */
@Data
public class PromptTemplateQuery {

    private long current = 1;
    private long size = 10;
    private String keyword;
    /** 业务键精确匹配；管理端中栏按对象取全部版本时使用，与模糊 keyword 区分 */
    private String templateKey;
    private String purpose;
    private String locale;
    private Integer status;
    private Integer isActive;
}

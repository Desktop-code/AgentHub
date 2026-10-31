package com.agenthub.prompt.api.dto;

import lombok.Data;

/**
 * L1 版本列表条件。所属域：prompt。
 */
@Data
public class PromptConstraintListReq {

    /** 语言；空则默认 zh-CN */
    private String locale;
}

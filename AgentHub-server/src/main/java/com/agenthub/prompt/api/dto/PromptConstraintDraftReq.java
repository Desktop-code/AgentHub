package com.agenthub.prompt.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * L1 保存草稿。所属域：prompt。
 * id 空：按 locale 新建下一版本草稿；id 有值且为草稿：原地更新正文。
 */
@Data
public class PromptConstraintDraftReq {

    /** 已有草稿主键；新建不传 */
    private Long id;
    /** 语言；空回落 zh-CN */
    private String locale;
    /** 平台约束正文 */
    @NotBlank(message = "content 不能为空")
    private String content;
}

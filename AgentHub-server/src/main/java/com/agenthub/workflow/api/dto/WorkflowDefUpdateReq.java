package com.agenthub.workflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新工作流元数据入参。所属域：workflow。
 * <p>只允许改名称与说明；code、状态、默认发布指针均不可经此接口修改。
 */
@Data
public class WorkflowDefUpdateReq {

    /** 中文名 */
    @NotBlank(message = "工作流名称不能为空")
    @Size(max = 128, message = "工作流名称最多 128 字")
    private String name;

    /** 说明 */
    @Size(max = 512, message = "说明最多 512 字")
    private String description;
}

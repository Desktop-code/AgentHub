package com.agenthub.workflow.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 保存应用-工作流授权的请求体。所属域：workflow 的 api。
 * <p>语义是「整体对齐」：列表里没有的 def 会被撤销授权；空列表 = 清空。
 */
@Data
public class WorkflowAppGrantSaveReq {

    /** 目标工作流定义 id；允许空列表表示撤销全部 */
    @NotNull(message = "workflowIds 不能为 null，撤销请传空数组")
    private List<Long> workflowIds;
}

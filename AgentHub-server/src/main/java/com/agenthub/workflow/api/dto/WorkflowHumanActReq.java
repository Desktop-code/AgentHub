package com.agenthub.workflow.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人工待办处理请求。所属域：workflow 的 api。
 * <p>approve / reject 共用；note 最多 512 字，直接进 result_json。
 */
@Data
public class WorkflowHumanActReq {

    /** 审批 / 驳回备注 */
    @Size(max = 512, message = "备注最多 512 字")
    private String note;
}

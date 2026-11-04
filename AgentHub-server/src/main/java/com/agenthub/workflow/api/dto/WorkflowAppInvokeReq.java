package com.agenthub.workflow.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * OpenAPI 触发工作流的请求体。所属域：workflow 的 api。
 * <p>只接受 workflowId：应用只能触发自己获授权的定义，且强制跑默认发布快照；
 * 不允许传 releaseId 绕过多版本授权（P1 只做默认指针，钉死版本由 grant 行决定）。
 */
@Data
public class WorkflowAppInvokeReq {

    /** workflow_def.id */
    @NotNull(message = "workflowId 不能为空")
    private Long workflowId;

    /** 调用输入，必须是 JSON 对象；空表示 {} */
    private JsonNode input;

    /**
     * 调用方幂等键，可空。
     * <p>同 app 下重复提交同一个值会返回已创建的 run，不会重复触发。
     */
    private String clientRequestId;
}

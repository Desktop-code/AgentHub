package com.agenthub.workflow.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工待办响应。所属域：workflow 的 api。
 * <p>试跑抽屉据此渲染「等待人工」区块与通过 / 驳回按钮。
 */
@Data
public class WorkflowHumanTaskResp {

    private Long id;
    private Long runId;
    private Long nodeRunId;
    private String nodeCode;
    private String status;
    private String assigneeRule;
    private String timeoutPolicy;
    private LocalDateTime expireTime;
    private String resultJson;
}

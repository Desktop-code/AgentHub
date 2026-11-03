package com.agenthub.workflow.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OpenAPI 触发工作流的响应。所属域：workflow 的 api。
 * <p>只回「创建结果」，不等待引擎跑完；调用方用 runId 自行查询 / 接入后续观测。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowAppInvokeResp {

    /** run 主键 */
    private Long runId;
    /** 创建时状态（CREATED，异步调度会很快推进） */
    private String status;
    /** workflow_def.id */
    private Long workflowId;
    /** 实际绑定的发布版本 id */
    private Long releaseId;
    /** 触发类型，固定 APP */
    private String triggerType;
}

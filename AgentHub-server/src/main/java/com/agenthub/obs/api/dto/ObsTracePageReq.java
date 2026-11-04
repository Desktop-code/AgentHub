package com.agenthub.obs.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 中栏 trace 分页。所属域：obs。q 模糊匹配 title。
 * workflowId（=workflow_def.id）非空时按工作流过滤，此时不需要 sessionId；
 * 对话场景走 {@code /api/obs/sessions/{sessionId}/traces}，不带该字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ObsTracePageReq extends PageQuery {

    private String q;

    /** 工作流定义 id；仅在按工作流查询 trace 时使用 */
    private Long workflowId;
}

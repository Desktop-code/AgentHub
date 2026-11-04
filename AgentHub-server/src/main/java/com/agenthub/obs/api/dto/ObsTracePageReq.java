package com.agenthub.obs.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 中栏 trace 分页。所属域：obs。q 模糊匹配 title。
 * workflowId（=workflow_def.id）非空时按该定义过滤，为空时查全部工作流运行记录；
 * 对话场景走 {@code /api/obs/sessions/{sessionId}/traces}，不带该字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ObsTracePageReq extends PageQuery {

    private String q;

    /** 工作流定义 id；按工作流查询 trace 时使用，缺省表示全部工作流运行记录 */
    private Long workflowId;
}

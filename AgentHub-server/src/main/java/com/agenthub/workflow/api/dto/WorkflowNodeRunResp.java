package com.agenthub.workflow.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点运行态响应。所属域：workflow 的 api。
 * <p>画布高亮按 {@code nodeId + status} 上色；{@code selectedEdges} 供后续画边高亮。
 */
@Data
public class WorkflowNodeRunResp {

    private Long id;
    private String nodeId;
    private String nodeCode;
    private String nodeType;
    private Integer attempt;
    private String status;
    private String errorSummary;
    /** 选中的出边 id，逗号分隔 */
    private String selectedEdges;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}

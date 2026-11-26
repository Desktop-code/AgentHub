package com.agenthub.workflow.api.dto;

import com.agenthub.workflow.domain.model.WorkflowNodeRun;
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

    /**
     * 节点行 → 响应。详情与调试直播共用同一份字段口径。
     *
     * @param row 节点运行态；可空
     * @return 响应；入参为空时返回 {@code null}
     */
    public static WorkflowNodeRunResp of(WorkflowNodeRun row) {
        if (row == null) {
            return null;
        }
        WorkflowNodeRunResp item = new WorkflowNodeRunResp();
        item.setId(row.getId());
        item.setNodeId(row.getNodeId());
        item.setNodeCode(row.getNodeCode());
        item.setNodeType(row.getNodeType());
        item.setAttempt(row.getAttempt());
        item.setStatus(row.getStatus());
        item.setErrorSummary(row.getErrorSummary());
        item.setSelectedEdges(row.getSelectedEdges());
        item.setStartTime(row.getStartTime());
        item.setEndTime(row.getEndTime());
        return item;
    }
}

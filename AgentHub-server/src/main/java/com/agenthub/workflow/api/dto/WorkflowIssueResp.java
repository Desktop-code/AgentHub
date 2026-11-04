package com.agenthub.workflow.api.dto;

import com.agenthub.workflow.domain.model.WorkflowIssue;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条校验问题出参。所属域：workflow。
 * <p>nodeId / nodeCode 供前端点击定位节点。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowIssueResp {

    private String code;
    private String message;
    private String nodeId;
    private String nodeCode;

    /**
     * 由领域问题转换。
     *
     * @param issue 领域问题
     * @return 出参
     */
    public static WorkflowIssueResp of(WorkflowIssue issue) {
        return new WorkflowIssueResp(issue.getCode(), issue.getMessage(),
                issue.getNodeId(), issue.getNodeCode());
    }
}

package com.agenthub.workflow.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 图校验出参。所属域：workflow。
 * <p>passed=false 时发布会被拒绝；warns 不影响发布。
 */
@Data
public class WorkflowValidateResp {

    private List<WorkflowIssueResp> errors = new ArrayList<>();
    private List<WorkflowIssueResp> warns = new ArrayList<>();
    /** errors 为空即 true */
    private boolean passed;
}

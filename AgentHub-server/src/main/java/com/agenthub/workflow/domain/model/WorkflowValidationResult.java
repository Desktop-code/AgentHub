package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 图校验结果。所属域：workflow 的 domain。
 * <p>errors 非空即不可发布；warns 只做提示，不阻断发布。
 */
@Data
public class WorkflowValidationResult {

    /** 阻拦项：发布必须为 0 */
    private List<WorkflowIssue> errors = new ArrayList<>();
    /** 提示项：不阻断保存/发布 */
    private List<WorkflowIssue> warns = new ArrayList<>();

    /**
     * 是否可发布。
     *
     * @return true 表示没有 error
     */
    public boolean passed() {
        return errors.isEmpty();
    }
}

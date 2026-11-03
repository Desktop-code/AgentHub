package com.agenthub.workflow.domain;

/**
 * 节点执行失败。所属域：workflow 的 domain。
 * <p>只承载「已脱敏、可直接进 error_summary」的中文说明；原始异常放 cause，
 * 供日志查栈，禁止写进 run 明细展示。
 */
public class WorkflowNodeFailure extends RuntimeException {

    /**
     * @param message 中文失败说明
     */
    public WorkflowNodeFailure(String message) {
        super(message);
    }

    /**
     * @param message 中文失败说明
     * @param cause   原始异常，只用于日志
     */
    public WorkflowNodeFailure(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.agenthub.workflow.domain;

/**
 * 草稿并发写冲突。所属域：workflow 的 domain。
 * <p>触发：PUT 草稿带的 expectedUpdateTime 与库里不一致（他人已保存）。
 * 由 workflow 域的异常处理器转成 HTTP 409，前端提示刷新后重试。
 */
public class WorkflowConflictException extends RuntimeException {

    /**
     * @param message 给前端看的中文说明
     */
    public WorkflowConflictException(String message) {
        super(message);
    }
}

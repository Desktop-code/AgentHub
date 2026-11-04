package com.agenthub.workflow.api;

import com.agenthub.common.api.Result;
import com.agenthub.workflow.domain.WorkflowConflictException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 工作流域异常转换。所属域：workflow。
 * <p>只处理草稿并发冲突（HTTP 409），并限本域控制器，避免影响其它域。
 * <p>为什么单开一个 advice：common 的 ErrorCode 没有 409 语义，且本刀不允许改公共域；
 * 用 {@code HIGHEST_PRECEDENCE} 抢在全局兜底 Exception 处理器之前命中。
 */
@RestControllerAdvice(basePackages = "com.agenthub.workflow")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkflowExceptionAdvice {

    /** 草稿并发冲突的业务码；HTTP 409 */
    static final int CONFLICT_CODE = 40901;

    /**
     * @param ex 并发冲突
     * @return HTTP 409 + 统一 Result
     */
    @ExceptionHandler(WorkflowConflictException.class)
    public ResponseEntity<Result<Void>> handleConflict(WorkflowConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.fail(CONFLICT_CODE, ex.getMessage()));
    }
}

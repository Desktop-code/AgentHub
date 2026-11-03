package com.agenthub.common.error;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.agenthub.common.api.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常转换。所属域：common。
 * 约束：未知异常只打日志，响应里不出现堆栈、SQL 或 Token。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务失败，HTTP 状态由错误码决定。
     *
     * @param ex 业务异常
     * @return 带业务码的响应
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        ErrorCode code = ex.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus()).body(Result.fail(code.getCode(), ex.getMessage()));
    }

    /**
     * Bean Validation 失败时把字段说明拼进 msg。
     *
     * @param ex 校验异常
     * @return HTTP 400
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleInvalid(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(this::fieldMessage)
                .collect(Collectors.joining("；"));
        return badRequest(msg.isBlank() ? ErrorCode.BAD_REQUEST.getMessage() : msg);
    }

    /**
     * 路径或查询参数上的校验失败。
     *
     * @param ex 约束异常
     * @return HTTP 400
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException ex) {
        return badRequest(ex.getMessage());
    }

    /**
     * 请求体读失败：区分「根本不是 JSON」与「字段类型不匹配」，避免一律「格式不正确」误导评测导入。
     *
     * @param ex 读体异常
     * @return HTTP 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String detail = cause == null || cause.getMessage() == null ? "" : cause.getMessage();
        // 真·非法 JSON（截断、尾逗号等）
        if (cause instanceof com.fasterxml.jackson.core.JsonParseException) {
            return badRequest("请求体不是合法 JSON");
        }
        // 常见：enabled 写成布尔却未走宽松反序列化的其它 DTO
        if (detail.contains("enabled") && detail.contains("Boolean")) {
            return badRequest("字段 enabled 只能是 0/1 或 true/false");
        }
        if (cause instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException) {
            return badRequest("请求体字段类型不匹配，请核对导入 JSON 与接口契约");
        }
        return badRequest("请求体格式不正确");
    }

    /**
     * 唯一键冲突。软删后名字仍占用时也会走到这里。
     *
     * @param ex 数据库唯一约束异常
     * @return HTTP 400
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicate(DuplicateKeyException ex) {
        log.info("唯一键冲突: {}", ex.getMostSpecificCause().getMessage());
        return badRequest("数据已存在（已删除的同名记录也不会释放唯一键）");
    }

    /**
     * 未登录。不区分过期、踢下线等细节，避免把 Token 状态暴露给调用方。
     *
     * @param ex Sa-Token 未登录异常
     * @return HTTP 401
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException ex) {
        ErrorCode code = ErrorCode.UNAUTHORIZED;
        return ResponseEntity.status(code.getHttpStatus()).body(Result.fail(code.getCode(), code.getMessage()));
    }

    /**
     * 已登录但权限码或角色不匹配。
     *
     * @param ex 无权限
     * @return HTTP 403
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<Result<Void>> handleForbidden(RuntimeException ex) {
        ErrorCode code = ErrorCode.FORBIDDEN;
        return ResponseEntity.status(code.getHttpStatus()).body(Result.fail(code.getCode(), code.getMessage()));
    }

    /**
     * 兜底。日志保留堆栈，响应只给固定文案。
     *
     * @param ex 未分类异常
     * @return HTTP 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception ex) {
        log.error("未处理异常", ex);
        ErrorCode code = ErrorCode.INTERNAL;
        return ResponseEntity.status(code.getHttpStatus()).body(Result.fail(code.getCode(), code.getMessage()));
    }

    private ResponseEntity<Result<Void>> badRequest(String msg) {
        ErrorCode code = ErrorCode.BAD_REQUEST;
        return ResponseEntity.status(code.getHttpStatus()).body(Result.fail(code.getCode(), msg));
    }

    private String fieldMessage(FieldError error) {
        return error.getField() + " " + (error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage());
    }
}

package com.agenthub.common.error;

import lombok.Getter;

/**
 * 可预期的业务失败。所属域：common。由全局异常处理器转成 Result，不带堆栈给前端。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 使用错误码自带文案。
     *
     * @param errorCode 业务错误码
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 使用更具体的文案，HTTP 状态仍跟错误码走。
     *
     * @param errorCode 业务错误码
     * @param message   返回给前端的说明
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

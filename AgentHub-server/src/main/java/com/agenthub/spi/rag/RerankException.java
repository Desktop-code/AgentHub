package com.agenthub.spi.rag;

/**
 * 重排出站失败。所属域：spi。
 * 文案不得含 Key；用途未适配时由实现抛出带「用途未适配」的本异常。
 */
public class RerankException extends RuntimeException {

    /**
     * @param message 对人说明，已脱敏
     */
    public RerankException(String message) {
        super(message);
    }

    /**
     * @param message 对人说明
     * @param cause   根因（日志可记，勿回前端堆栈）
     */
    public RerankException(String message, Throwable cause) {
        super(message, cause);
    }
}

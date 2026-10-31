package com.agenthub.spi.rag;

/**
 * 向量化失败。所属域：spi。
 * message 会写进入库任务的 error_msg 并回显给管理员，因此**不得包含 API Key、完整 URL 查询串或密文**。
 */
public class EmbeddingException extends RuntimeException {

    /**
     * @param message 面向管理员的失败原因（不含密钥）
     */
    public EmbeddingException(String message) {
        super(message);
    }

    /**
     * @param message 面向管理员的失败原因（不含密钥）
     * @param cause   底层异常，仅用于日志
     */
    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}

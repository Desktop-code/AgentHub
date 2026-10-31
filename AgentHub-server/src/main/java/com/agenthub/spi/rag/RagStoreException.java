package com.agenthub.spi.rag;

/**
 * Store 层失败。所属域：spi。由 RagProvider 抛出，应用服务据此把知识库标记为 INIT_FAILED。
 * <p>
 * message 会写进 rag_knowledge_base.error_msg 并回显给管理员，因此不得包含密钥、连接串密码。
 */
public class RagStoreException extends RuntimeException {

    /**
     * @param message 面向管理员的失败原因（不含密钥）
     */
    public RagStoreException(String message) {
        super(message);
    }

    /**
     * @param message 面向管理员的失败原因（不含密钥）
     * @param cause   底层异常，仅用于日志
     */
    public RagStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

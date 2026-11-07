package com.agenthub.memory.domain.service;

/**
 * 记忆向量存储失败。所属域：memory。
 * <p>
 * 只用于「初始化/连通失败」这类可降级错误：上层捕获后把 isReady 置 false 并打日志，
 * 不阻断应用启动，也不影响 MySQL 记忆主路径。消息禁止拼接任何密钥/连接串凭证。
 */
public class MemoryVectorStoreException extends RuntimeException {

    /**
     * @param message 可读失败原因（不含密钥）
     */
    public MemoryVectorStoreException(String message) {
        super(message);
    }

    /**
     * @param message 可读失败原因（不含密钥）
     * @param cause   底层异常
     */
    public MemoryVectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

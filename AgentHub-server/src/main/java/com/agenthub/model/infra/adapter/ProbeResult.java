package com.agenthub.model.infra.adapter;

/**
 * 探活结果。所属域：model。message 必须已经去掉 Key。
 *
 * @param success      是否探活成功
 * @param latencyMs    耗时
 * @param message      给人看的原因
 * @param embeddingDim 连通成功时解析出的向量维度快照；仅 EMBEDDING 用途可能有值，其余恒为 null
 */
public record ProbeResult(boolean success, long latencyMs, String message, Integer embeddingDim) {

    /**
     * 非嵌入用途与失败场景的便捷构造：不带维度快照。
     *
     * @param success   是否成功
     * @param latencyMs 耗时
     * @param message   脱敏说明
     */
    public ProbeResult(boolean success, long latencyMs, String message) {
        this(success, latencyMs, message, null);
    }

    /**
     * 用途未适配：不发起 HTTP，success=false，避免 HTTP 可达被当成模型可用。
     *
     * @param message 须含「用途未适配」与协议×用途
     * @return 失败结果，耗时 0
     */
    public static ProbeResult unsupported(String message) {
        return new ProbeResult(false, 0L, message);
    }
}

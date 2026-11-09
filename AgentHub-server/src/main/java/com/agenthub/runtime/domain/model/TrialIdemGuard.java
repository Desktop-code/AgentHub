package com.agenthub.runtime.domain.model;

import com.agenthub.runtime.api.dto.MessageResp;

/**
 * 一次发消息的幂等占位结果。所属域：runtime。
 * <p>
 * 由 {@code TrialMessageIdempotency#begin} 产出：
 * <ul>
 *   <li>{@link #none()}：请求没带幂等键，或开关关闭——按现网行为直跑，不做去重；</li>
 *   <li>{@link #managed}：已占位，可以执行；跑完必须 {@code complete} 或 {@code release}；</li>
 *   <li>{@link #replay}：同键同内容已完成，直接返回 {@link #replay()} 的等价结果。</li>
 * </ul>
 * 「已占位」是**必须释放**的资源：执行前失败要 {@code release} 删掉占位，否则该键会被占死到 TTL。
 */
public final class TrialIdemGuard {

    private final Long sessionId;
    private final String idemKey;
    /** 首次提交时的内容指纹；收尾回写与接管都需要它 */
    private final String fingerprint;
    private final MessageResp replay;

    private TrialIdemGuard(Long sessionId, String idemKey, String fingerprint, MessageResp replay) {
        this.sessionId = sessionId;
        this.idemKey = idemKey;
        this.fingerprint = fingerprint;
        this.replay = replay;
    }

    /**
     * @return 不托管（无键 / 开关关闭）
     */
    public static TrialIdemGuard none() {
        return new TrialIdemGuard(null, null, null, null);
    }

    /**
     * @param sessionId   会话
     * @param idemKey     已校验的幂等键
     * @param fingerprint 内容指纹
     * @return 已占位，调用方负责 complete / release
     */
    public static TrialIdemGuard managed(Long sessionId, String idemKey, String fingerprint) {
        return new TrialIdemGuard(sessionId, idemKey, fingerprint, null);
    }

    /**
     * @param replay 已存结果对应的等价响应
     * @return 命中重放
     */
    public static TrialIdemGuard replay(MessageResp replay) {
        return new TrialIdemGuard(replay == null ? null : replay.getSessionId(), null, null, replay);
    }

    /**
     * @return true 表示本次请求受幂等托管，跑完必须回写终态
     */
    public boolean managed() {
        return sessionId != null && idemKey != null && replay == null;
    }

    /**
     * @return true 表示命中重放，调用方应直接返回 {@link #replay()}
     */
    public boolean hasReplay() {
        return replay != null;
    }

    public MessageResp replay() {
        return replay;
    }

    public Long sessionId() {
        return sessionId;
    }

    public String idemKey() {
        return idemKey;
    }

    public String fingerprint() {
        return fingerprint;
    }
}

package com.agenthub.runtime.domain.model;

import lombok.Data;

/**
 * 一次「发消息」的幂等记录。所属域：runtime。
 * <p>
 * 真源是 MySQL 的 {@code agent_run_idempotency}（唯一键 session_id + idem_key），
 * Redis 只是同一记录的短 TTL 副本（快速判定 + 跨实例互斥），两者字段口径一致。
 * <p>
 * 状态机只有三态，且都**终态可判定**：
 * <ul>
 *   <li>{@link State#RUNNING}：某次请求已占位、正在跑（第二个人来必须拒绝，不能双跑）；</li>
 *   <li>{@link State#SUCCESS}：本轮已成功落库，重放直接返回等价结果；</li>
 *   <li>{@link State#FAILED}：本轮已失败收尾。**同样视为终态**——同一键重试不再重跑模型，
 *       否则一次失败重试就可能重复出站与重复计费。</li>
 * </ul>
 * RUNNING 超过陈旧窗口（进程崩溃 / 发布重启）会被后来的同键请求接管，避免键被永久占死。
 * <p>
 * 不存任何正文以外的敏感物：指纹是 SHA-256，预览/错误摘要已过 {@code TrialTexts} 脱敏与截断。
 */
@Data
public class TrialIdempotencyRecord {

    /** 幂等记录状态。枚举值说明见类注释，禁止扩展第四态 */
    public enum State {
        /** 已占位、执行中；同键再来一律拒绝 */
        RUNNING,
        /** 本轮成功；同键再来返回等价结果 */
        SUCCESS,
        /** 本轮失败；同键再来返回同一失败结果，不重跑模型 */
        FAILED
    }

    /** 会话主键；与 idemKey 组成唯一键 */
    private Long sessionId;
    /** 客户端幂等键（Header Idempotency-Key），已通过格式校验 */
    private String idemKey;
    /** 请求内容指纹：正文 + 图片的 SHA-256。键相同但指纹不同一律拒绝，禁静默覆盖 */
    private String fingerprint;
    private State state = State.RUNNING;
    /** 本轮 MessageResp.status，仅 SUCCESS / FAILED 时有值 */
    private String runStatus;
    /** 本轮助手预览，已脱敏截断；供重放时给前端一句可读结果 */
    private String assistantPreview;
    /** 本轮错误摘要，已脱敏截断 */
    private String errorSummary;
    /** 本轮是否停在「计划待批准」 */
    private boolean planPending;
    /**
     * 最近一次状态刷新时间（epoch 毫秒）。用于判定 RUNNING 是否已陈旧。
     * 用毫秒而不是 LocalDateTime：这条记录要序列化进 Redis，避免依赖时间类型模块配置。
     */
    private long updatedAtMs;

    /**
     * 是否已过期到可以接管。只对 RUNNING 有意义：SUCCESS / FAILED 永远不回退。
     *
     * @param nowMs    当前时间
     * @param staleSec 陈旧窗口（秒）；<=0 视为不可接管
     * @return true 表示可以让后来的同键请求重新占位
     */
    public boolean staleAt(long nowMs, long staleSec) {
        if (staleSec <= 0 || state != State.RUNNING) {
            return false;
        }
        return nowMs - updatedAtMs > staleSec * 1000L;
    }

    /**
     * @return true 表示该记录已经有可返回的终态结论
     */
    public boolean finished() {
        return state == State.SUCCESS || state == State.FAILED;
    }
}

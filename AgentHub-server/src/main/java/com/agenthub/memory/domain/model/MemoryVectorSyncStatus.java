package com.agenthub.memory.domain.model;

/**
 * 记忆行到向量库的同步状态。所属域：memory。
 * <p>
 * 只靠这一个字段表达同步进度，<b>不再加任何 {@code *_at} 时间列</b>：
 * 时间列在 MySQL 与 Milvus 双写场景下无法判断「内容是否已变」，状态位才能。
 * <p>
 * 状态流转（唯一口径）：
 * <ul>
 *   <li>{@link #PENDING}：写/改/重新启用后由双写挂载点同步打标；存量行为列默认值；</li>
 *   <li>{@link #SYNCED}：已成功写入，或该行当前不需要任何向量动作（已删 / 已过期 / 非 LONG / 空正文）；</li>
 *   <li>{@link #FAILED}：同步失败且失败次数未到上限，会被补同步下一轮重试。</li>
 * </ul>
 * 达上限后仍停在 FAILED、只打日志，不注册定时重炸，也不回滚 MySQL。
 */
public enum MemoryVectorSyncStatus {

    /** 待同步：内容已变但向量还没跟上 */
    PENDING,

    /** 无需动作：已同步成功，或该行本就不该有向量 */
    SYNCED,

    /** 同步失败：fail_count 未到上限时会被补同步重试 */
    FAILED
}

package com.agenthub.memory.domain.model;

import com.agenthub.memory.domain.service.MemoryVectorSchema;

/**
 * 一条待写入记忆向量库的行。所属域：memory。
 * <p>
 * 字段口径与 {@code MemoryVectorSchema} 一一对应：
 * {@code memory_id} 为主键（取 MySQL memory_item.id，upsert 天然幂等）；
 * {@code user_id}/{@code agent_id} 供召回隔离；{@code fact_key} 便于排障与同键更新；
 * {@code expired_time}/{@code importance} 供召回侧过滤与重排；{@code content} 是正文副本。
 * <p>
 * 正文副本不替代 MySQL：MySQL 仍是真源，向量侧存它是为了让「只靠主键回查」不再是取正文的唯一路径。
 * 所有值都来自同一行 MySQL 快照，构造时做归一化（负过期时间归 0、importance 收进 0~100、正文截断），
 * 保证写入侧不会因为上游脏数据造出「永远不该命中」的点。
 *
 * @param memoryId    memory_item.id，必须为正数
 * @param userId      平台用户；可空
 * @param agentId     Agent；可空
 * @param factKey     LONG 稳定事实键；可空（人工条允许无 key）
 * @param embedding   已 L2 归一化的向量，长度必须等于建集维度
 * @param expiredTime 过期时间（毫秒）；{@link MemoryVectorSchema#NO_EXPIRY_MILLIS} 表示永不过期
 * @param importance  重要程度 0~100，与 MySQL 同语义
 * @param content     正文副本（已按 {@link MemoryVectorSchema#CONTENT_MAX_LENGTH} 截断）
 */
public record MemoryVectorRow(long memoryId, Long userId, Long agentId, String factKey,
                              float[] embedding, long expiredTime, int importance, String content) {

    /**
     * 归一化：非法值就地纠正，避免「写入成功但永远命中不了」的隐形坏点。
     */
    public MemoryVectorRow {
        // 负的过期时间只可能来自上游脏数据；按「无过期」处理，由 MySQL 对齐与清理任务纠正
        if (expiredTime < MemoryVectorSchema.NO_EXPIRY_MILLIS) {
            expiredTime = MemoryVectorSchema.NO_EXPIRY_MILLIS;
        }
        // importance 与 MySQL 同域（0~100），越界压回而不是写进库让排序失真
        importance = Math.max(0, Math.min(importance, 100));
        // VarChar 不接受 null；超长按与 Embedding 相同的口径截断
        content = MemoryVectorSchema.clipContent(content);
    }
}

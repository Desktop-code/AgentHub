package com.agenthub.memory.domain.model;

/**
 * 一条向量召回命中。所属域：memory。
 * <p>
 * 携带 {@code memory_id}、相似度分数，以及召回侧需要的三个标量/正文：{@code content}、{@code importance}、
 * {@code expiredTime}。<b>MySQL 仍是真源</b>：调用方必须回 MySQL 校验「未删除 / 仍启用 / 未过期 / 正文漂移」，
 * 向量侧的正文只作为「主键回查拿不到正文」时的兜底，不单独决定注入内容。
 *
 * @param memoryId    memory_item.id
 * @param score       相似度分（IP + L2 归一化后等价余弦，越高越相关）
 * @param content     正文副本（截断后）；未要求返回时为 null
 * @param importance  重要程度 0~100；未要求返回时为 0
 * @param expiredTime 过期时间（毫秒），0 表示无过期；未要求返回时为 0
 */
public record MemoryVectorHit(long memoryId, float score, String content, int importance, long expiredTime) {

    /**
     * 只关心主键与分数的场景（历史调用、测试替身）；向量侧标量/正文缺失。
     *
     * @param memoryId 主键
     * @param score    分数
     */
    public MemoryVectorHit(long memoryId, float score) {
        this(memoryId, score, null, 0, 0L);
    }

    /**
     * @return 向量侧是否带回了正文（可用于 MySQL 正文为空时的兜底）
     */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}

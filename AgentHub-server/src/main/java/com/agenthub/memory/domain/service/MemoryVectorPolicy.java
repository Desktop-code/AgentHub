package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryScope;

import java.time.LocalDateTime;

/**
 * LONG 记忆「该不该有向量点」的唯一判定。所属域：memory 的 memory.domain.service。
 * <p>
 * 为什么单独抽出来：判断散落在双写 hook、过期清理、补同步三处时，只要有一处口径不同就会出现
 * 「MySQL 说没有向量、向量库里还留着点」或反过来的漂移。写入侧、联删侧、补同步侧必须调<b>同一个方法</b>。
 * <p>
 * 判定口径（与 MySQL 召回口径一致）：
 * <ul>
 *   <li>非 LONG → 不该有向量；</li>
 *   <li>{@code status != 1}（停用）→ 不该有向量；</li>
 *   <li>{@code expired_time} 存在且不晚于当前时刻（{@code <= now}，恰好到点即过期）→ 不该有向量；</li>
 *   <li>正文为空/空白 → 不该有向量（没东西可向量化）。</li>
 * </ul>
 * 线程安全：纯静态无状态，可在池线程任意调用。
 */
public final class MemoryVectorPolicy {

    private MemoryVectorPolicy() {
    }

    /**
     * 该行当前是否应存在向量点。
     *
     * @param item 记忆条目；null 视为不该有向量
     * @param now  判定用的当前时间，由调用方统一传入，避免同一批处理里各取一次时间导致口径不一致
     * @return true 表示应存在向量（→ upsert）；false 表示不该存在（→ delete-or-noop）
     */
    public static boolean shouldHaveVector(MemoryItem item, LocalDateTime now) {
        if (item == null || item.getId() == null) {
            return false;
        }
        if (!MemoryScope.LONG.name().equals(item.getScope())) {
            return false;
        }
        // status 缺省按启用处理（与 memory_item.status 默认 1 一致）
        if (item.getStatus() != null && item.getStatus() != 1) {
            return false;
        }
        LocalDateTime expired = item.getExpiredTime();
        if (expired != null && !expired.isAfter(now)) {
            return false;
        }
        return item.getContent() != null && !item.getContent().isBlank();
    }
}

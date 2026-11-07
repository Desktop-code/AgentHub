package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryScope;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.MemoryTexts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 显式记忆通道的落库事务边界。所属域：memory 的 application。
 *
 * <p>为什么单独一层：显式 L2 在 {@code memoryTaskExecutor} 池线程上执行，<b>不继承</b>
 * 请求线程的事务；若直接复用请求线程残留的事务语义，回答失败回滚时会把已经确认的
 * 「记住」一起回滚。这里用 {@link Propagation#REQUIRES_NEW} 明确开一个独立事务，
 * 只包住解析后的写库与超限淘汰，<b>不包住</b>最长 60s 的模型出站。
 *
 * <p>去重口径沿用现有 {@link LongMemoryMerger}：fact_key 同键合并，content_hash 作
 * 全文完全相同的短路径；本类不新增 source / is_active / valid_* 字段。
 */
@Service
public class ExplicitMemoryStore {

    private final LongMemoryMerger merger;
    private final MemoryItemRepository items;
    private final MemoryVectorHook vectors;

    /**
     * @param merger  LONG 同键合并（自带 fact_key 归一，并在内部挂向量双写）
     * @param items   超限淘汰
     * @param vectors 淘汰行的向量联动删除
     */
    public ExplicitMemoryStore(LongMemoryMerger merger, MemoryItemRepository items, MemoryVectorHook vectors) {
        this.merger = merger;
        this.items = items;
        this.vectors = vectors;
    }

    /**
     * 在一个独立事务里写入解析出的偏好，并按上限淘汰旧 LONG。
     *
     * @param agentId      Agent
     * @param userId       平台用户
     * @param drafts       L2 解析结果
     * @param maxLongItems 策略上限
     * @param operatorId   操作人（计量写 user_id）
     * @return 写入统计；单条密钥/缺 fact_key 只跳过该条，不影响其它条目
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome store(long agentId, long userId, List<MemoryTexts.PreferenceDraft> drafts,
                         int maxLongItems, long operatorId) {
        int inserted = 0;
        int updated = 0;
        int skippedSecret = 0;
        int skippedFactKey = 0;
        for (MemoryTexts.PreferenceDraft draft : drafts) {
            if (draft == null) {
                continue;
            }
            // 密钥拦截是写库前的硬闸：模型把密钥抄进正文时宁可不写，也不能落库
            try {
                MemoryTexts.rejectSecret(draft.getContent());
            } catch (RuntimeException ex) {
                skippedSecret++;
                continue;
            }
            Optional<Boolean> outcome = merger.upsertExtracted(agentId, userId, draft, operatorId);
            if (outcome.isEmpty()) {
                skippedFactKey++;
            } else if (Boolean.TRUE.equals(outcome.get())) {
                inserted++;
            } else {
                updated++;
            }
        }
        // 超限淘汰放进同一事务：避免「写入成功、淘汰失败」留下超量 LONG
        List<Long> evicted = items.evictOverflow(agentId, userId, MemoryScope.LONG.name(), maxLongItems, operatorId);
        // 淘汰行必须同步删向量，否则召回仍可能命中已淘汰的旧偏好
        vectors.afterRemoved(evicted);
        return new Outcome(inserted, updated, skippedSecret, skippedFactKey);
    }

    /**
     * 一次写入的结果统计，仅用于日志与测试。
     *
     * @param inserted      新建条数
     * @param updated       同键/同 hash 更新条数
     * @param skippedSecret 命中密钥拦截而跳过的条数
     * @param skippedFactKey 缺有效 fact_key 而跳过的条数
     */
    public record Outcome(int inserted, int updated, int skippedSecret, int skippedFactKey) {
    }
}

package com.agenthub.prompt.infra.cache;

import com.agenthub.prompt.domain.repository.PromptCacheInvalidator;
import com.agenthub.prompt.domain.service.PromptCacheKeys;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 提示词缓存失效器。所属域：prompt 的 infra。
 * <p>
 * 关键约束：失效必须发生在**事务提交之后**。
 * 若在事务内删缓存，提交前的那一刻并发读者会读到旧库值并把它写回缓存，
 * 于是提交完成后缓存里仍是旧数据，且没有任何路径能再触发失效——这正是架构定稿禁止的
 * 「永久脏读无失效路径」。因此优先注册 {@code afterCommit} 回调；不在事务里时立即执行。
 * <p>
 * 线程安全：无状态；回调由 Spring 事务同步器在提交线程上执行。
 */
@Component
public class PromptCacheInvalidatorImpl implements PromptCacheInvalidator {

    private final CachedPromptSnapshotProvider cache;

    /**
     * @param cache 需要失效的缓存装饰器（本地层 + Redis 层）
     */
    public PromptCacheInvalidatorImpl(CachedPromptSnapshotProvider cache) {
        this.cache = cache;
    }

    /** {@inheritDoc} */
    @Override
    public void evictConstraint(String locale) {
        afterCommit(() -> cache.evict(PromptCacheKeys.constraint(locale)));
    }

    /** {@inheritDoc} */
    @Override
    public void evictTemplateRef(String templateKey, String locale) {
        afterCommit(() -> cache.evict(PromptCacheKeys.templateActive(templateKey, locale)));
    }

    /** {@inheritDoc} */
    @Override
    public void evictPurposeRef(String purpose, String locale) {
        afterCommit(() -> cache.evict(PromptCacheKeys.purposeActive(purpose, locale)));
    }

    /**
     * 在事务提交后执行；无事务时立即执行（例如单元测试或后台脚本直接调用）。
     *
     * @param action 失效动作
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}

package com.agenthub.runtime.infra.cache;

import com.agenthub.runtime.domain.service.AgentDictCacheKeys;
import com.agenthub.spi.cache.AgentDictCacheInvalidator;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 运行期字典缓存失效器。所属域：runtime 的 infra。
 * <p>
 * 关键约束：失效必须发生在**事务提交之后**。
 * 若在事务内删键，提交前的那一刻并发读者会读到旧库值并把它写回缓存，
 * 于是提交完成后缓存里仍是旧数据，且没有任何路径能再触发失效——正是架构禁止的
 * 「永久脏读无失效路径」。因此优先注册 {@code afterCommit} 回调；不在事务里时立即执行。
 * <p>
 * 失败口径：回调里查 id 或删键异常一律只记 warn，绝不向调用方（写接口）抛出；
 * 即使本次没删掉，Redis TTL 与负缓存 TTL 也会兜底。
 * <p>
 * 线程安全：无状态；回调由 Spring 事务同步器在提交线程上执行。
 */
@Component
public class AgentDictCacheInvalidatorImpl implements AgentDictCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(AgentDictCacheInvalidatorImpl.class);

    private final CachedAgentDictionary cache;
    private final ToolItemRepository items;

    /**
     * @param cache 需要失效的缓存装饰器
     * @param items 工具源失效时枚举源下工具项 id（只取 id 用途的列表查询）
     */
    public AgentDictCacheInvalidatorImpl(CachedAgentDictionary cache, ToolItemRepository items) {
        this.cache = cache;
        this.items = items;
    }

    /** {@inheritDoc} */
    @Override
    public void evictToolItems(Collection<Long> itemIds) {
        List<String> keys = toolItemKeys(itemIds);
        if (keys.isEmpty()) {
            return;
        }
        afterCommit(() -> cache.evictAll(keys));
    }

    /** {@inheritDoc} */
    @Override
    public void evictToolSource(Long sourceId) {
        if (sourceId == null || sourceId <= 0) {
            return;
        }
        afterCommit(() -> {
            cache.evict(AgentDictCacheKeys.toolSource(sourceId));
            // 源停用/改名会影响其下工具项的装配结果（源不可用即整源下线），故连带失效
            evictItemsOfSource(sourceId);
        });
    }

    /** {@inheritDoc} */
    @Override
    public void evictSkills(Collection<Long> skillIds) {
        List<String> keys = skillKeys(skillIds);
        if (keys.isEmpty()) {
            return;
        }
        afterCommit(() -> cache.evictAll(keys));
    }

    /**
     * 清掉某源下当前未删除工具项的键。放在提交后执行，读到的是已提交数据。
     * 逻辑删除的项查不到，需由调用方用 {@link #evictToolItems(Collection)} 补上删除前的 id。
     *
     * @param sourceId 工具源主键
     */
    private void evictItemsOfSource(Long sourceId) {
        List<String> keys = new ArrayList<>();
        try {
            for (ToolItem item : items.listBySourceId(sourceId)) {
                if (item.getId() != null) {
                    keys.add(AgentDictCacheKeys.toolItem(item.getId()));
                }
            }
        } catch (RuntimeException ex) {
            // 枚举失败只记日志：源键已经删了，工具项键还有 TTL 兜底
            log.warn("运行期字典缓存按源枚举工具项失败 sourceId={} type={}",
                    sourceId, ex.getClass().getSimpleName());
            return;
        }
        cache.evictAll(keys);
    }

    /**
     * 工具项 id → 键，去重并跳过非法 id。
     *
     * @param itemIds 工具项主键，可空
     * @return 待删键列表；无有效 id 时为空
     */
    private static List<String> toolItemKeys(Collection<Long> itemIds) {
        Set<String> keys = new LinkedHashSet<>();
        if (itemIds != null) {
            for (Long id : itemIds) {
                if (id != null && id > 0) {
                    keys.add(AgentDictCacheKeys.toolItem(id));
                }
            }
        }
        return new ArrayList<>(keys);
    }

    /**
     * Skill id → 键，去重并跳过非法 id。
     *
     * @param skillIds Skill 主键，可空
     * @return 待删键列表；无有效 id 时为空
     */
    private static List<String> skillKeys(Collection<Long> skillIds) {
        Set<String> keys = new LinkedHashSet<>();
        if (skillIds != null) {
            for (Long id : skillIds) {
                if (id != null && id > 0) {
                    keys.add(AgentDictCacheKeys.skillDef(id));
                }
            }
        }
        return new ArrayList<>(keys);
    }

    /**
     * 在事务提交后执行；无事务时立即执行（单测、启动期回填等）。
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

package com.agenthub.auth.infra.cache;

import com.agenthub.auth.domain.service.AclCacheKeys;
import com.agenthub.auth.domain.service.AclCacheStore;
import com.agenthub.spi.cache.AclCacheInvalidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 访问快照缓存失效器。所属域：auth 的 infra。
 * <p>
 * 关键约束：失效必须发生在**事务提交之后**。
 * 若在事务内推进版本或删键，提交前的那一刻并发读者会用旧库值回填新版本的键，
 * 提交完成后没有任何路径能再触发失效——正是架构禁止的「永久脏读无失效路径」。
 * 因此优先注册 {@code afterCommit} 回调；不在事务里时立即执行。
 * <p>
 * 两种失效粒度：
 * <ul>
 *   <li>{@link #evictUsers} 精确：读当前版本后删这些用户的键（分配角色 / 停用 / 删除用户）。
 *       并发下有极小窗口可能被回填一次旧值，由快照 TTL 收敛；</li>
 *   <li>{@link #evictAll} 整体：推进全局版本（角色授权、角色启停、菜单增删改），旧键靠 TTL 清理。</li>
 * </ul>
 * <p>
 * 失败口径：读版本或删键异常一律只记 warn，绝不向写接口抛出；TTL 是最后兜底。
 * 线程安全：无状态；回调由 Spring 事务同步器在提交线程上执行。
 */
@Component
public class AclCacheInvalidatorImpl implements AclCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(AclCacheInvalidatorImpl.class);

    private final AclCacheStore store;

    /**
     * @param store 只需版本读写与删键，不依赖序列化逻辑
     */
    public AclCacheInvalidatorImpl(AclCacheStore store) {
        this.store = store;
    }

    /** {@inheritDoc} */
    @Override
    public void evictUsers(Collection<Long> userIds) {
        List<Long> ids = validIds(userIds);
        if (ids.isEmpty()) {
            return;
        }
        afterCommit(() -> {
            // 版本在提交后读取，保证删的是「读者现在会命中的那一个版本」
            Optional<Long> version = store.currentVersion();
            if (version.isEmpty()) {
                // Redis 不可用：读者本来就走库，无需再删；TTL 兜底
                log.warn("访问快照精确失效跳过：版本不可读 size={}", ids.size());
                return;
            }
            List<String> keys = ids.stream().map(id -> AclCacheKeys.snapshot(id, version.get())).toList();
            store.evictAll(keys);
        });
    }

    /** {@inheritDoc} */
    @Override
    public void evictAll() {
        afterCommit(() -> {
            long version = store.bumpVersion();
            if (version < 0) {
                log.warn("访问快照整体失效失败，靠 TTL 兜底");
                return;
            }
            log.info("访问快照整体失效，版本推进到 {}", version);
        });
    }

    /**
     * 去重并剔除非法主键，避免拼出 {@code snapshot:0} 这类无意义键。
     *
     * @param userIds 用户主键；可空
     * @return 合法 id 列表
     */
    private static List<Long> validIds(Collection<Long> userIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (userIds != null) {
            for (Long id : userIds) {
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * 在事务提交后执行；无事务时立即执行（单测、启动期任务）。
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

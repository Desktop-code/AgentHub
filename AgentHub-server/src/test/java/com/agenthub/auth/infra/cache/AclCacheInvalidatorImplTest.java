package com.agenthub.auth.infra.cache;

import com.agenthub.auth.domain.service.AclCacheKeys;
import com.agenthub.auth.domain.service.AclCacheStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 访问快照失效时机与粒度。所属域：auth。
 * <p>
 * 两个必须成立的语义：
 * 1. 失效在**事务提交后**才发生（否则并发读者会把旧值写回新版本键，形成永久脏读）；
 * 2. 单用户变更只删该用户的键，不能误伤其它用户。
 */
class AclCacheInvalidatorImplTest {

    /** 内存 Redis：记录版本与键 */
    private static final class MemoryStore implements AclCacheStore {
        final Map<String, String> data = new ConcurrentHashMap<>();
        volatile long version;

        @Override
        public Optional<Long> currentVersion() {
            return Optional.of(version);
        }

        @Override
        public long bumpVersion() {
            return ++version;
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            data.put(key, value);
        }

        @Override
        public void evict(String key) {
            data.remove(key);
        }

        @Override
        public void evictAll(Collection<String> keys) {
            keys.forEach(data::remove);
        }
    }

    /**
     * 提交前版本不变、键不删；提交后精确删掉目标用户的键，其它用户不受影响。
     */
    @Test
    void preciseEvictionRunsAfterCommitAndSparesOthers() {
        MemoryStore store = new MemoryStore();
        store.version = 5;
        store.data.put(AclCacheKeys.snapshot(1L, 5L), "u1");
        store.data.put(AclCacheKeys.snapshot(2L, 5L), "u2");
        AclCacheInvalidatorImpl invalidator = new AclCacheInvalidatorImpl(store);

        TransactionSynchronizationManager.initSynchronization();
        invalidator.evictUsers(List.of(1L));
        // 回调未触发：键仍在，证明失效没有提前发生
        assertTrue(store.data.containsKey(AclCacheKeys.snapshot(1L, 5L)));

        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertFalse(synchronizations.isEmpty());
        for (var synchronization : synchronizations) {
            synchronization.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();

        assertNull(store.data.get(AclCacheKeys.snapshot(1L, 5L)), "目标用户键应被删除");
        assertEquals("u2", store.data.get(AclCacheKeys.snapshot(2L, 5L)), "其它用户不得误伤");
        assertEquals(5L, store.version, "精确失效不改全局版本");
    }

    /**
     * 整体失效：提交前版本不动，提交后推进版本。
     */
    @Test
    void globalEvictionBumpsVersionAfterCommit() {
        MemoryStore store = new MemoryStore();
        AclCacheInvalidatorImpl invalidator = new AclCacheInvalidatorImpl(store);

        TransactionSynchronizationManager.initSynchronization();
        invalidator.evictAll();
        assertEquals(0L, store.version, "提交前不得推进版本");

        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();

        assertEquals(1L, store.version);
    }

    /**
     * 不在事务里时立即执行（单测、启动期任务、后台脚本）。
     */
    @Test
    void evictionRunsImmediatelyWithoutTransaction() {
        MemoryStore store = new MemoryStore();
        store.version = 2;
        store.data.put(AclCacheKeys.snapshot(7L, 2L), "u7");
        AclCacheInvalidatorImpl invalidator = new AclCacheInvalidatorImpl(store);

        invalidator.evictUsers(List.of(7L));
        assertNull(store.data.get(AclCacheKeys.snapshot(7L, 2L)));

        invalidator.evictAll();
        assertEquals(3L, store.version);
    }

    /**
     * 空集合与非法主键不产生任何动作，避免拼出 snapshot:0 这类无意义键。
     */
    @Test
    void invalidIdsAreIgnored() {
        MemoryStore store = new MemoryStore();
        store.data.put(AclCacheKeys.snapshot(0L, 0L), "zero");
        AclCacheInvalidatorImpl invalidator = new AclCacheInvalidatorImpl(store);

        invalidator.evictUsers(null);
        invalidator.evictUsers(List.of());
        invalidator.evictUsers(java.util.Arrays.asList(null, 0L, -3L));

        assertEquals("zero", store.data.get(AclCacheKeys.snapshot(0L, 0L)), "非法 id 不得产生删除动作");
        assertEquals(0L, store.version);
    }
}

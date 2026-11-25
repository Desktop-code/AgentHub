package com.agenthub.prompt.infra.cache;

import com.agenthub.prompt.domain.model.PromptConstraintSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateRef;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;
import com.agenthub.prompt.domain.repository.PromptSnapshotProvider;
import com.agenthub.prompt.domain.service.PromptCacheKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存失效时机：必须在事务提交后清。所属域：prompt。
 * <p>
 * 这是架构定稿 §7.7 的落点：允许短暂旧读，不允许「永久脏读无失效路径」。
 * 若在事务内清缓存，并发读者会在提交前把旧值写回缓存，提交后再没人清——本测正是防这个。
 */
class PromptCacheInvalidatorImplTest {

    private final ObjectMapper json = new ObjectMapper();

    /** 最简假 DB：L1 正文可控 */
    private static final class FakeDb implements PromptSnapshotProvider {
        volatile String constraint = "L1 旧版";

        @Override
        public Optional<PromptConstraintSnapshot> activeConstraint(String locale) {
            return Optional.of(new PromptConstraintSnapshot(locale, 1, constraint));
        }

        @Override
        public Optional<PromptTemplateSnapshot> templateByKey(String templateKey, String locale, int version) {
            return Optional.empty();
        }

        @Override
        public Optional<PromptTemplateSnapshot> templateById(Long id) {
            return Optional.empty();
        }

        @Override
        public Optional<PromptTemplateRef> activeRefByPurpose(String purpose, String locale) {
            return Optional.empty();
        }

        @Override
        public Optional<PromptTemplateRef> activeRefByKey(String templateKey, String locale) {
            return Optional.empty();
        }
    }

    /** 内存 Redis，记录删除过的键 */
    private static final class MemoryStore implements com.agenthub.prompt.domain.service.PromptCacheStore {
        final Map<String, String> data = new ConcurrentHashMap<>();

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
    }

    private CachedPromptSnapshotProvider provider(FakeDb db, MemoryStore store) {
        PromptCacheProperties properties = new PromptCacheProperties();
        properties.setLocalTtlSec(60);
        properties.setRedisTtlSec(60);
        return new CachedPromptSnapshotProvider(db, store, json, properties);
    }

    /**
     * 提交后回调：提交前旧值仍可读，提交后才真正失效并读到新值。
     */
    @Test
    void evictionRunsAfterCommit() {
        FakeDb db = new FakeDb();
        MemoryStore store = new MemoryStore();
        CachedPromptSnapshotProvider cache = provider(db, store);
        PromptCacheInvalidatorImpl invalidator = new PromptCacheInvalidatorImpl(cache);

        // 先暖缓存
        assertEquals("L1 旧版", cache.activeConstraint("zh-CN").orElseThrow().getContent());

        // 模拟一个活动事务：注册回调后不立刻失效
        TransactionSynchronizationManager.initSynchronization();
        db.constraint = "L1 新版";
        invalidator.evictConstraint("zh-CN");
        // 回调尚未触发：本地缓存仍是旧值，证明失效没有提前发生
        assertEquals("L1 旧版", cache.activeConstraint("zh-CN").orElseThrow().getContent());

        // 提交：手动触发 afterCommit
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertFalse(synchronizations.isEmpty());
        for (var synchronization : synchronizations) {
            synchronization.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();

        assertEquals("L1 新版", cache.activeConstraint("zh-CN").orElseThrow().getContent());
    }

    /**
     * 不在事务里时立即失效：后台脚本 / 单测直接调用也要能刷掉缓存。
     */
    @Test
    void evictionRunsImmediatelyWithoutTransaction() {
        FakeDb db = new FakeDb();
        MemoryStore store = new MemoryStore();
        CachedPromptSnapshotProvider cache = provider(db, store);
        PromptCacheInvalidatorImpl invalidator = new PromptCacheInvalidatorImpl(cache);

        cache.activeConstraint("zh-CN");
        db.constraint = "L1 立即刷新";
        invalidator.evictConstraint("zh-CN");

        assertEquals("L1 立即刷新", cache.activeConstraint("zh-CN").orElseThrow().getContent());
    }

    /**
     * 三个失效方法都要落到各自的键上，不能只清一个。
     */
    @Test
    void allThreeKeysAreEvicted() {
        FakeDb db = new FakeDb();
        MemoryStore store = new MemoryStore();
        CachedPromptSnapshotProvider cache = provider(db, store);
        PromptCacheInvalidatorImpl invalidator = new PromptCacheInvalidatorImpl(cache);

        // 手工塞三个键，验证各自被删
        store.data.put(PromptCacheKeys.constraint("zh-CN"), "x");
        store.data.put(PromptCacheKeys.purposeActive("CHAT", "zh-CN"), "x");
        store.data.put(PromptCacheKeys.templateActive("CHAT", "zh-CN"), "x");

        invalidator.evictConstraint("zh-CN");
        invalidator.evictPurposeRef("CHAT", "zh-CN");
        invalidator.evictTemplateRef("CHAT", "zh-CN");

        assertTrue(store.data.isEmpty(), "三个键都应被删除");
    }
}

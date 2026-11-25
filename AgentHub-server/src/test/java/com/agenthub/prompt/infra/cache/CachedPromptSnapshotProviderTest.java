package com.agenthub.prompt.infra.cache;

import com.agenthub.prompt.domain.model.PromptConstraintSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateRef;
import com.agenthub.prompt.domain.model.PromptSegmentSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;
import com.agenthub.prompt.domain.repository.PromptSnapshotProvider;
import com.agenthub.prompt.domain.service.PromptCacheKeys;
import com.agenthub.prompt.domain.service.PromptCacheStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词快照缓存：三级读取、失效、负缓存、单飞防击穿。所属域：prompt。
 * <p>
 * 用内存假 DB 与内存假 Redis，不连 MySQL、不连 Redis 实例；
 * 断言重点是「DB 被打了多少次」与「发布后能否读到新值」，这两条正是防击穿与
 * 无永久脏读的可执行判据。
 */
class CachedPromptSnapshotProviderTest {

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 计数假 DB：每次调用 +1，用于验证缓存真的生效。
     * 非 final 以便个别用例覆写「确认不存在」的行为。
     */
    private static class CountingDb implements PromptSnapshotProvider {
        final AtomicInteger constraintCalls = new AtomicInteger();
        final AtomicInteger templateCalls = new AtomicInteger();
        volatile String constraintText = "L1 v1";
        volatile int constraintVersion = 1;

        @Override
        public Optional<PromptConstraintSnapshot> activeConstraint(String locale) {
            constraintCalls.incrementAndGet();
            return Optional.of(new PromptConstraintSnapshot(locale, constraintVersion, constraintText));
        }

        @Override
        public Optional<PromptTemplateSnapshot> templateByKey(String templateKey, String locale, int version) {
            templateCalls.incrementAndGet();
            PromptTemplateSnapshot snapshot = new PromptTemplateSnapshot();
            snapshot.setId(1L);
            snapshot.setTemplateKey(templateKey);
            snapshot.setPurpose(templateKey);
            snapshot.setLocale(locale);
            snapshot.setVersion(version);
            snapshot.setSegments(List.of(new PromptSegmentSnapshot("core_contract", "契约", 10, 0, 1, "CORE")));
            return Optional.of(snapshot);
        }

        @Override
        public Optional<PromptTemplateSnapshot> templateById(Long id) {
            templateCalls.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public Optional<PromptTemplateRef> activeRefByPurpose(String purpose, String locale) {
            return Optional.of(new PromptTemplateRef(1L, purpose, purpose, locale, 1));
        }

        @Override
        public Optional<PromptTemplateRef> activeRefByKey(String templateKey, String locale) {
            return Optional.of(new PromptTemplateRef(1L, templateKey, templateKey, locale, 1));
        }
    }

    /**
     * 内存 Redis：可注入读失败，用于验证「缓存抖动降级到 DB」。
     */
    private static final class MemoryStore implements PromptCacheStore {
        final Map<String, String> data = new ConcurrentHashMap<>();
        volatile boolean failReads;

        @Override
        public Optional<String> get(String key) {
            if (failReads) {
                return Optional.empty();
            }
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

    private static PromptCacheProperties properties() {
        PromptCacheProperties properties = new PromptCacheProperties();
        properties.setLocalTtlSec(60);
        properties.setRedisTtlSec(60);
        properties.setMissTtlSec(1);
        return properties;
    }

    /**
     * 第一次打库，之后命中本地层；同一 locale 的第二次调用不得再打库。
     */
    @Test
    void secondReadHitsLocalCacheWithoutTouchingDb() {
        CountingDb db = new CountingDb();
        CachedPromptSnapshotProvider provider =
                new CachedPromptSnapshotProvider(db, new MemoryStore(), json, properties());

        provider.activeConstraint("zh-CN");
        provider.activeConstraint("zh-CN");
        provider.activeConstraint("zh-CN");

        assertEquals(1, db.constraintCalls.get(), "本地缓存命中后不得再打 DB");
    }

    /**
     * 本地层清掉后仍能从 Redis 命中，DB 依旧只被打一次。
     */
    @Test
    void redisLayerServesWhenLocalEvicted() {
        CountingDb db = new CountingDb();
        MemoryStore store = new MemoryStore();
        CachedPromptSnapshotProvider provider = new CachedPromptSnapshotProvider(db, store, json, properties());

        provider.activeConstraint("zh-CN");
        // 只清本地层：新建一个只共享 Redis 的实例，模拟另一台机器/重启后的进程
        CachedPromptSnapshotProvider other = new CachedPromptSnapshotProvider(db, store, json, properties());
        other.activeConstraint("zh-CN");

        assertEquals(1, db.constraintCalls.get(), "Redis 命中后不得再打 DB");
    }

    /**
     * 发布失效：清本地 + 清 Redis 后必须能读到新值，不允许永久脏读。
     */
    @Test
    void evictAfterPublishReadsNewValue() {
        CountingDb db = new CountingDb();
        MemoryStore store = new MemoryStore();
        CachedPromptSnapshotProvider provider = new CachedPromptSnapshotProvider(db, store, json, properties());

        assertEquals("L1 v1", provider.activeConstraint("zh-CN").orElseThrow().getContent());
        // 模拟发布：DB 换版 + 失效缓存
        db.constraintText = "L1 v2";
        db.constraintVersion = 2;
        provider.evict(PromptCacheKeys.constraint("zh-CN"));

        PromptConstraintSnapshot after = provider.activeConstraint("zh-CN").orElseThrow();
        assertEquals("L1 v2", after.getContent());
        assertEquals(2, after.getVersion());
        assertEquals(2, db.constraintCalls.get(), "失效后必须重新打库一次");
    }

    /**
     * Redis 抖动：读失败降级为 miss，继续打 DB，结果照常返回（允许的降级）。
     */
    @Test
    void redisFailureDegradesToDb() {
        CountingDb db = new CountingDb();
        MemoryStore store = new MemoryStore();
        store.failReads = true;
        CachedPromptSnapshotProvider provider = new CachedPromptSnapshotProvider(db, store, json, properties());

        Optional<PromptConstraintSnapshot> hit = provider.activeConstraint("zh-CN");

        assertTrue(hit.isPresent());
        assertEquals("L1 v1", hit.get().getContent());
        assertEquals(1, db.constraintCalls.get());
    }

    /**
     * 负缓存：DB 确认不存在时也要缓存，短时间内不再打库（避免缺配置把 DB 打穿）。
     */
    @Test
    void missingResultIsNegativelyCached() {
        CountingDb base = new CountingDb();
        PromptSnapshotProvider db = new PromptSnapshotProvider() {
            @Override
            public Optional<PromptConstraintSnapshot> activeConstraint(String locale) {
                base.constraintCalls.incrementAndGet();
                return Optional.empty();
            }

            @Override
            public Optional<PromptTemplateSnapshot> templateByKey(String key, String locale, int version) {
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
        };
        CachedPromptSnapshotProvider provider =
                new CachedPromptSnapshotProvider(db, new MemoryStore(), json, properties());

        assertTrue(provider.activeConstraint("zh-CN").isEmpty());
        assertTrue(provider.activeConstraint("zh-CN").isEmpty());
        assertTrue(provider.activeConstraint("zh-CN").isEmpty());

        assertEquals(1, base.constraintCalls.get(), "负缓存期内不得反复打 DB");
    }

    /**
     * 钉死版与按用途生效引用分开缓存：读 v1 不会因为 active 指向 v2 而串版。
     */
    @Test
    void pinnedVersionIsCachedSeparatelyFromActiveRef() {
        CountingDb db = new CountingDb();
        CachedPromptSnapshotProvider provider =
                new CachedPromptSnapshotProvider(db, new MemoryStore(), json, properties());

        PromptTemplateSnapshot v1 = provider.templateByKey("CHAT", "zh-CN", 1).orElseThrow();
        PromptTemplateSnapshot v2 = provider.templateByKey("CHAT", "zh-CN", 2).orElseThrow();

        assertEquals(1, v1.getVersion());
        assertEquals(2, v2.getVersion());
        // 两个版本各打一次库，说明键按版本区分
        assertEquals(2, db.templateCalls.get());
    }

    /**
     * 单飞：同一冷 key 的 8 个并发请求只应产生 1 次 DB 调用。
     * 这是防缓存击穿的直接证据——没有单飞时冷 key 会同时穿透。
     */
    @Test
    void concurrentColdKeyLoadsOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PromptSnapshotProvider slowDb = new PromptSnapshotProvider() {
            @Override
            public Optional<PromptConstraintSnapshot> activeConstraint(String locale) {
                calls.incrementAndGet();
                try {
                    // 放大窗口，让并发线程确实同时到达
                    Thread.sleep(80);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return Optional.of(new PromptConstraintSnapshot(locale, 1, "L1 单飞"));
            }

            @Override
            public Optional<PromptTemplateSnapshot> templateByKey(String key, String locale, int version) {
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
        };
        CachedPromptSnapshotProvider provider =
                new CachedPromptSnapshotProvider(slowDb, new MemoryStore(), json, properties());

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (provider.activeConstraint("zh-CN").isEmpty()) {
                        failures.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "并发加载应在超时内完成");
        assertEquals(0, failures.get(), "所有等待者都应拿到结果");
        assertEquals(1, calls.get(), "单飞应把同键并发压成 1 次 DB 调用");
    }
}

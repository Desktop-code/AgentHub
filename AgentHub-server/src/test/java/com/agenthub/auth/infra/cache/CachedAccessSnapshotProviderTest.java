package com.agenthub.auth.infra.cache;

import com.agenthub.admin.domain.model.Menu;
import com.agenthub.admin.domain.model.User;
import com.agenthub.auth.domain.model.AccessSnapshot;
import com.agenthub.auth.domain.service.AccessSnapshotProvider;
import com.agenthub.auth.domain.service.AclCacheKeys;
import com.agenthub.auth.domain.service.AclCacheStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 访问快照缓存：命中、版本切换、降级、坏值回库、停用不进缓存、单飞。所属域：auth。
 * <p>
 * 用内存假 Redis 与计数假 DB，不连 Redis / MySQL。断言重点是：
 * 「DB 被算了几次」「坏值会不会把权限读成空（403）」「版本推进后能不能读到新值」——
 * 这三条正是本刀的可执行判据。
 */
class CachedAccessSnapshotProviderTest {

    private final ObjectMapper json = new ObjectMapper();

    /** 计数假 DB：每次调用 +1；内容可改，用于验证失效后读到新值 */
    private static final class CountingProvider implements AccessSnapshotProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile List<String> permissions = List.of("tool:list");
        volatile List<Menu> menus = List.of();

        @Override
        public AccessSnapshot snapshot(User user) {
            calls.incrementAndGet();
            return new AccessSnapshot(user.getId(), List.of("ops"), new ArrayList<>(permissions),
                    new ArrayList<>(menus));
        }
    }

    /** 内存 Redis：可注入读失败 / 版本不可读 */
    private static final class MemoryStore implements AclCacheStore {
        final Map<String, String> data = new ConcurrentHashMap<>();
        volatile long version;
        volatile boolean failReads;
        volatile boolean versionUnavailable;

        @Override
        public Optional<Long> currentVersion() {
            return versionUnavailable ? Optional.empty() : Optional.of(version);
        }

        @Override
        public long bumpVersion() {
            return ++version;
        }

        @Override
        public Optional<String> get(String key) {
            return failReads ? Optional.empty() : Optional.ofNullable(data.get(key));
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

    private static User user(Long id, int status) {
        User user = new User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setStatus(status);
        return user;
    }

    private static AclCacheProperties properties() {
        AclCacheProperties properties = new AclCacheProperties();
        properties.setEnabled(true);
        properties.setRedisTtlSec(60);
        return properties;
    }

    private CachedAccessSnapshotProvider provider(CountingProvider db, MemoryStore store) {
        return new CachedAccessSnapshotProvider(db, store, json, properties());
    }

    /**
     * 同一用户第二次读必须命中 Redis，不再算库。
     */
    @Test
    void secondReadHitsCache() {
        CountingProvider db = new CountingProvider();
        MemoryStore store = new MemoryStore();
        CachedAccessSnapshotProvider cache = provider(db, store);

        assertEquals(List.of("tool:list"), cache.snapshot(user(1L, 1)).getPermissions());
        assertEquals(List.of("tool:list"), cache.snapshot(user(1L, 1)).getPermissions());

        assertEquals(1, db.calls.get(), "命中缓存后不得再算库");
        assertTrue(store.data.containsKey(AclCacheKeys.snapshot(1L, 0L)));
    }

    /**
     * 推进全局版本后，读者拿到新键并读到新权限：这是「改角色/菜单后立即生效」的判据。
     */
    @Test
    void bumpedVersionReadsFreshData() {
        CountingProvider db = new CountingProvider();
        MemoryStore store = new MemoryStore();
        CachedAccessSnapshotProvider cache = provider(db, store);

        assertEquals(List.of("tool:list"), cache.snapshot(user(1L, 1)).getPermissions());

        // 模拟管理员改了角色菜单：DB 换值 + 版本推进（真实路径由写入口 afterCommit 触发）
        db.permissions = List.of("tool:list", "tool:create");
        store.bumpVersion();

        assertEquals(List.of("tool:list", "tool:create"), cache.snapshot(user(1L, 1)).getPermissions());
        assertEquals(2, db.calls.get(), "版本变化后必须重新算一次");
    }

    /**
     * Redis 读失败降级为算库，结果照常返回（允许的降级），且不写键。
     */
    @Test
    void redisFailureDegradesToDb() {
        CountingProvider db = new CountingProvider();
        MemoryStore store = new MemoryStore();
        store.failReads = true;
        CachedAccessSnapshotProvider cache = provider(db, store);

        assertEquals(List.of("tool:list"), cache.snapshot(user(1L, 1)).getPermissions());
        assertEquals(List.of("tool:list"), cache.snapshot(user(1L, 1)).getPermissions());

        assertEquals(2, db.calls.get(), "缓存不可用时每次都要算库");
    }

    /**
     * 版本不可读（Redis 抖动或值损坏）时直接算库，不猜版本。
     */
    @Test
    void unavailableVersionDegradesToDb() {
        CountingProvider db = new CountingProvider();
        MemoryStore store = new MemoryStore();
        store.versionUnavailable = true;
        CachedAccessSnapshotProvider cache = provider(db, store);

        cache.snapshot(user(1L, 1));
        cache.snapshot(user(1L, 1));

        assertEquals(2, db.calls.get());
        assertTrue(store.data.isEmpty(), "版本不可判定时不得产生任何键");
    }

    /**
     * 坏 JSON 必须回库重读，绝不能把「读不到」当成「没有权限」——空权限等于 403。
     */
    @Test
    void corruptJsonRefetchesInsteadOfEmpty() {
        CountingProvider db = new CountingProvider();
        MemoryStore store = new MemoryStore();
        CachedAccessSnapshotProvider cache = provider(db, store);
        store.data.put(AclCacheKeys.snapshot(1L, 0L), "{not-json");

        AccessSnapshot snapshot = cache.snapshot(user(1L, 1));

        assertEquals(List.of("tool:list"), snapshot.getPermissions());
        assertEquals(1, db.calls.get());
        // 坏值已被剔除并回填为正确值
        assertFalse(store.data.get(AclCacheKeys.snapshot(1L, 0L)).contains("not-json"));
    }

    /**
     * 快照里 userId 与请求用户不一致（键被人工挪动/串值）时必须回库，禁止返回别人的权限码。
     */
    @Test
    void snapshotOfAnotherUserIsRejected() throws Exception {
        CountingProvider db = new CountingProvider();
        MemoryStore store = new MemoryStore();
        CachedAccessSnapshotProvider cache = provider(db, store);
        AccessSnapshot foreign = new AccessSnapshot(999L, List.of("hacker"), List.of("admin:all"), List.of());
        store.data.put(AclCacheKeys.snapshot(1L, 0L), json.writeValueAsString(foreign));

        AccessSnapshot snapshot = cache.snapshot(user(1L, 1));

        assertEquals(1L, snapshot.getUserId());
        assertEquals(List.of("tool:list"), snapshot.getPermissions(), "不得返回其它用户的权限码");
        assertEquals(1, db.calls.get());
    }

    /**
     * 停用用户不进缓存：权限必须当场归零，且不产生任何键。
     */
    @Test
    void disabledUserBypassesCache() {
        CountingProvider db = new CountingProvider();
        MemoryStore store = new MemoryStore();
        CachedAccessSnapshotProvider cache = provider(db, store);

        cache.snapshot(user(1L, 0));
        cache.snapshot(user(1L, 0));

        assertEquals(2, db.calls.get());
        assertTrue(store.data.isEmpty(), "停用用户不得写入缓存");
    }

    /**
     * 开关关闭时整链退回直读库。
     */
    @Test
    void disabledCacheBypassesRedis() {
        CountingProvider db = new CountingProvider();
        MemoryStore store = new MemoryStore();
        AclCacheProperties off = properties();
        off.setEnabled(false);
        CachedAccessSnapshotProvider cache = new CachedAccessSnapshotProvider(db, store, json, off);

        cache.snapshot(user(1L, 1));
        cache.snapshot(user(1L, 1));

        assertEquals(2, db.calls.get());
        assertTrue(store.data.isEmpty());
    }

    /**
     * 空权限是有效快照（没勾任何菜单的用户），照常缓存：第二次不再算库。
     */
    @Test
    void emptyPermissionSnapshotIsCachedPositively() {
        CountingProvider db = new CountingProvider();
        db.permissions = List.of();
        MemoryStore store = new MemoryStore();
        CachedAccessSnapshotProvider cache = provider(db, store);

        assertTrue(cache.snapshot(user(1L, 1)).getPermissions().isEmpty());
        assertTrue(cache.snapshot(user(1L, 1)).getPermissions().isEmpty());

        assertEquals(1, db.calls.get(), "空权限也要缓存，否则每个注解校验都打库");
    }

    /**
     * 单飞：同一冷键的 8 个并发请求只应产生 1 次算库（防缓存击穿）。
     */
    @Test
    void concurrentColdKeyLoadsOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AccessSnapshotProvider slow = user -> {
            calls.incrementAndGet();
            try {
                // 放大窗口，让并发线程确实同时到达
                Thread.sleep(80);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new AccessSnapshot(user.getId(), List.of(), List.of("tool:list"), List.of());
        };
        CachedAccessSnapshotProvider cache =
                new CachedAccessSnapshotProvider(slow, new MemoryStore(), json, properties());

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (cache.snapshot(user(1L, 1)).getPermissions().isEmpty()) {
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
        assertEquals(0, failures.get());
        assertEquals(1, calls.get(), "单飞应把同键并发压成 1 次算库");
    }
}

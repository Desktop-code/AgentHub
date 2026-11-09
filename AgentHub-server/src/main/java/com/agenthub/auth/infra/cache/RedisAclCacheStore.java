package com.agenthub.auth.infra.cache;

import com.agenthub.auth.domain.service.AclCacheKeys;
import com.agenthub.auth.domain.service.AclCacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;

/**
 * 访问快照缓存的 Redis 实现。所属域：auth 的 infra。
 * <p>
 * 作用：多实例共享「权限码 + 菜单树」，让 /me 与每个 {@code @SaCheckPermission} 不再各自打 3~5 次查询。
 * <p>
 * 容错口径：Redis 不可用时**必须**降级，不允许抛错。
 * 允许的降级是「读不到版本 → 直接查库」；把 Redis 异常抛上去会让一次抖动变成全站无法鉴权。
 * 因此本类所有方法都吞异常并只记 warn（键名 + 异常类型，不含值，避免把权限/菜单正文写进日志）。
 * <p>
 * 版本键 {@code agenthub:acl:ver} 刻意**不设 TTL**：若它自然过期并从 0 重新计数，
 * 历史上写过的 {@code v0} 快照键可能被重新命中，形成「回退到旧权限」。
 * Redis 整体清空时快照键同时消失，所以不会出现旧值复活。
 */
@Repository
public class RedisAclCacheStore implements AclCacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAclCacheStore.class);

    private final StringRedisTemplate redis;

    /**
     * @param redis 字符串模板，与应用其它 Redis 用途共用连接
     */
    public RedisAclCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Long> currentVersion() {
        try {
            String raw = redis.opsForValue().get(AclCacheKeys.version());
            // 键不存在等价于版本 0：首次 INCR 会把它推到 1，之前的 v0 键只可能来自 Redis 从未写过版本的空窗
            if (raw == null || raw.isBlank()) {
                return Optional.of(0L);
            }
            return Optional.of(Long.parseLong(raw.trim()));
        } catch (RuntimeException ex) {
            // 解析失败也走这里：版本不可判定时必须回库，绝不能猜一个版本去读缓存
            log.warn("访问快照版本读取失败，已降级到 DB key={} type={}",
                    AclCacheKeys.version(), ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override
    public long bumpVersion() {
        try {
            Long version = redis.opsForValue().increment(AclCacheKeys.version());
            return version == null ? -1L : version;
        } catch (RuntimeException ex) {
            // 推进失败只影响「是否能立刻失效」；TTL 与精确删仍是兜底，写接口照常成功
            log.warn("访问快照版本推进失败，靠 TTL 兜底 key={} type={}",
                    AclCacheKeys.version(), ex.getClass().getSimpleName());
            return -1L;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException ex) {
            log.warn("访问快照缓存读取失败，已降级到 DB key={} type={}", key, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void put(String key, String value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException ex) {
            // 回填失败不影响本次结果：本次已经从 DB 拿到真值
            log.warn("访问快照缓存写入失败 key={} type={}", key, ex.getClass().getSimpleName());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            log.warn("访问快照缓存删除失败 key={} type={}", key, ex.getClass().getSimpleName());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void evictAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            redis.delete(keys);
        } catch (RuntimeException ex) {
            log.warn("访问快照缓存批量删除失败 size={} type={}", keys.size(), ex.getClass().getSimpleName());
        }
    }
}

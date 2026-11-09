package com.agenthub.runtime.infra.cache;

import com.agenthub.runtime.domain.service.AgentDictCacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;

/**
 * 运行期字典缓存的 Redis 实现。所属域：runtime 的 infra。
 * <p>
 * 作用：多实例共享同一份字典快照，避免每台机器各自打库；键规范见 {@code AgentDictCacheKeys}。
 * <p>
 * 容错口径（与 prompt 域 {@code RedisPromptCacheStore} 同口径）：Redis 不可用时**必须**降级为 miss，
 * 不允许抛错。允许的降级是「缓存层失败但 DB 读成功」；把 Redis 异常抛上去会让一次抖动变成全站不可运行。
 * 因此本类所有方法都吞异常，只记 warn（键名 + 异常类型，不含值，避免把正文写进日志）。
 */
@Repository
public class RedisAgentDictCacheStore implements AgentDictCacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAgentDictCacheStore.class);

    private final StringRedisTemplate redis;

    /**
     * @param redis 字符串模板，与应用其它 Redis 用途共用连接
     */
    public RedisAgentDictCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException ex) {
            log.warn("运行期字典缓存读取失败，已降级到 DB key={} type={}", key, ex.getClass().getSimpleName());
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
            log.warn("运行期字典缓存写入失败 key={} type={}", key, ex.getClass().getSimpleName());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            // 删失败靠 TTL 兜底；写接口仍返回成功，不让缓存故障拖垮业务写
            log.warn("运行期字典缓存删除失败 key={} type={}", key, ex.getClass().getSimpleName());
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
            log.warn("运行期字典缓存批量删除失败 size={} type={}", keys.size(), ex.getClass().getSimpleName());
        }
    }
}

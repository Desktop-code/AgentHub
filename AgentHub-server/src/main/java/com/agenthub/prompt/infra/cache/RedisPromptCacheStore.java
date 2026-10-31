package com.agenthub.prompt.infra.cache;

import com.agenthub.prompt.domain.service.PromptCacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 提示词缓存的 Redis 实现。所属域：prompt 的 infra。
 * <p>
 * 作用：多实例共享 L1/L2 快照，避免每台机器各自打库。
 * <p>
 * 容错口径：Redis 不可用时**必须**降级为 miss 而不是抛错——
 * 设计允许的降级就是「缓存层失败但 DB 读成功」；把 Redis 异常抛上去会让一次抖动变成全站不可对话。
 * 因此本类所有方法都吞异常并只记 warn（键名与异常类型，不含正文）。
 */
@Repository
public class RedisPromptCacheStore implements PromptCacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisPromptCacheStore.class);

    private final StringRedisTemplate redis;

    /**
     * @param redis 字符串模板，与应用其它 Redis 用途共用连接
     */
    public RedisPromptCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException ex) {
            log.warn("提示词缓存读取失败，已降级到 DB key={} type={}", key, ex.getClass().getSimpleName());
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
            log.warn("提示词缓存写入失败 key={} type={}", key, ex.getClass().getSimpleName());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            // 删失败时靠 TTL 兜底；发布方仍会清本地缓存，故不是永久脏读
            log.warn("提示词缓存删除失败 key={} type={}", key, ex.getClass().getSimpleName());
        }
    }
}

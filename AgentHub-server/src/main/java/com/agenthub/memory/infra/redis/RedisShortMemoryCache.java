package com.agenthub.memory.infra.redis;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.service.ShortMemoryCache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * SHORT 的 Redis 实现。所属域：memory。
 * 读到 key 不存在才是 miss；空数组是命中。TTL 由调用方按 expired_time 计算。
 */
@Repository
public class RedisShortMemoryCache implements ShortMemoryCache {

    private static final TypeReference<List<MemoryItem>> LIST = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    /**
     * @param redis 字符串模板，连接失败时由调用方转成业务错误
     * @param json  与应用相同的时间格式
     */
    public RedisShortMemoryCache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<List<MemoryItem>> read(long agentId, long sessionId) {
        String raw = redis.opsForValue().get(key(agentId, sessionId));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            List<MemoryItem> items = json.readValue(raw, LIST);
            return Optional.of(items == null ? List.of() : items);
        } catch (Exception ex) {
            throw new IllegalStateException("短期记忆缓存无法解析");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void write(long agentId, long sessionId, List<MemoryItem> items, int ttlSec) {
        try {
            String raw = json.writeValueAsString(items == null ? List.of() : items);
            redis.opsForValue().set(key(agentId, sessionId), raw, Duration.ofSeconds(Math.max(ttlSec, 1)));
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("短期记忆缓存写入失败");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(long agentId, long sessionId) {
        redis.delete(key(agentId, sessionId));
    }

    /**
     * @param agentId   Agent
     * @param sessionId 会话
     * @return 与窗口、摘要都不同的 key
     */
    public static String key(long agentId, long sessionId) {
        return "agenthub:memory:short:" + agentId + ":" + sessionId;
    }
}

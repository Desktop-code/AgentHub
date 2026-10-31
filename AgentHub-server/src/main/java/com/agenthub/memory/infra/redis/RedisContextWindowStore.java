package com.agenthub.memory.infra.redis;

import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.service.ContextWindowStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 对话窗口的 Redis 实现。所属域：memory。
 * 窗口和摘要分两个 key，读写都滑动续期。
 */
@Repository
public class RedisContextWindowStore implements ContextWindowStore {

    private static final TypeReference<List<ContextTurn>> LIST = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    /**
     * @param redis 字符串模板
     * @param json  轮次序列化
     */
    public RedisContextWindowStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<List<ContextTurn>> readTurns(long sessionId) {
        String raw = redis.opsForValue().get(turnKey(sessionId));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            List<ContextTurn> turns = json.readValue(raw, LIST);
            return Optional.of(turns == null ? List.of() : turns);
        } catch (Exception ex) {
            throw new IllegalStateException("上下文窗口无法解析");
        }
    }

    /** {@inheritDoc} */
    @Override
    public String readSummary(long sessionId) {
        String raw = redis.opsForValue().get(summaryKey(sessionId));
        return raw == null ? "" : raw;
    }

    /** {@inheritDoc} */
    @Override
    public void save(long sessionId, String summary, List<ContextTurn> turns, int ttlSec) {
        Duration ttl = Duration.ofSeconds(Math.max(ttlSec, 1));
        try {
            String raw = json.writeValueAsString(turns == null ? List.of() : turns);
            redis.opsForValue().set(turnKey(sessionId), raw, ttl);
            redis.opsForValue().set(summaryKey(sessionId), summary == null ? "" : summary, ttl);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("上下文窗口写入失败");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(long sessionId) {
        redis.delete(List.of(turnKey(sessionId), summaryKey(sessionId)));
    }

    /**
     * @param sessionId 会话
     * @return 近轮列表 key
     */
    public static String turnKey(long sessionId) {
        return "agenthub:ctx:" + sessionId;
    }

    /**
     * @param sessionId 会话
     * @return 摘要槽 key
     */
    public static String summaryKey(long sessionId) {
        return "agenthub:ctx:summary:" + sessionId;
    }
}

package com.agenthub.runtime.infra.idempotency;

import com.agenthub.runtime.domain.model.TrialIdempotencyRecord;
import com.agenthub.runtime.domain.service.TrialIdemCacheKeys;
import com.agenthub.runtime.domain.service.TrialIdemStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 发消息幂等记录的 Redis 快路径。所属域：runtime 的 infra。
 * <p>
 * 职责只有一个：把「同会话 + 同幂等键」的并发请求在跨实例层面收成一次。
 * 真正的兜底在 {@code agent_run_idempotency} 的唯一键，所以这里**允许失败**：
 * 读写异常一律吞掉（只记 warn），并用「返回空 / 返回 null」把调用方导向 DB 判定。
 * <p>
 * 绝不能因为 Redis 抖动就返回 empty 让调用方以为「没有记录」——因此 {@link #putIfAbsent}
 * 用三态 {@code Boolean}：{@code null} 明确表示「Redis 不可用，别信我」。
 * <p>
 * 值里只有状态、指纹、预览与错误摘要（均已在写库前脱敏截断），不含 token / 密钥 / 正文全文。
 */
@Repository
public class RedisTrialIdemStore implements TrialIdemStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTrialIdemStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    /**
     * @param redis 字符串模板，与应用其它 Redis 用途共用连接
     * @param json  容器配置的序列化器
     */
    public RedisTrialIdemStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TrialIdempotencyRecord> get(Long sessionId, String idemKey) {
        String key = TrialIdemCacheKeys.trialMessage(sessionId, idemKey);
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(json.readValue(raw, TrialIdempotencyRecord.class));
        } catch (RuntimeException | JsonProcessingException ex) {
            // 反序列化失败按「读不到」处理：上层会落 DB 判定，绝不会因此放行双跑
            log.warn("发消息幂等记录读取失败，已回落 DB 判定 key={} type={}", key, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Boolean putIfAbsent(TrialIdempotencyRecord record, Duration ttl) {
        String key = TrialIdemCacheKeys.trialMessage(record.getSessionId(), record.getIdemKey());
        try {
            String value = json.writeValueAsString(record);
            Boolean ok = redis.opsForValue().setIfAbsent(key, value, ttl);
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("发消息幂等记录占位失败，已回落 DB 唯一键 key={} type={}", key, ex.getClass().getSimpleName());
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void put(TrialIdempotencyRecord record, Duration ttl) {
        String key = TrialIdemCacheKeys.trialMessage(record.getSessionId(), record.getIdemKey());
        try {
            redis.opsForValue().set(key, json.writeValueAsString(record), ttl);
        } catch (RuntimeException | JsonProcessingException ex) {
            // 回写失败不影响本轮结论：DB 已经是真源
            log.warn("发消息幂等记录回写失败 key={} type={}", key, ex.getClass().getSimpleName());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(Long sessionId, String idemKey) {
        String key = TrialIdemCacheKeys.trialMessage(sessionId, idemKey);
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            log.warn("发消息幂等记录删除失败 key={} type={}", key, ex.getClass().getSimpleName());
        }
    }
}

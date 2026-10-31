package com.agenthub.prompt.infra.cache;

import com.agenthub.prompt.domain.model.PromptConstraintSnapshot;
import com.agenthub.prompt.domain.model.PromptTemplateRef;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;
import com.agenthub.prompt.domain.repository.PromptSnapshotProvider;
import com.agenthub.prompt.domain.service.PromptCacheKeys;
import com.agenthub.prompt.domain.service.PromptCacheStore;
import com.agenthub.prompt.domain.service.SingleFlight;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 提示词快照的缓存装饰器。所属域：prompt 的 infra。
 * <p>
 * 读取路径（架构定稿 §5.3）：<b>Caffeine（进程内）→ Redis → DB</b>，命中即返回；
 * 未命中用单飞加载，避免冷 key 并发打穿 MySQL。
 * <p>
 * 空值也缓存（短 TTL 负缓存）：否则「没配置 L1」的每一次调用都会打库。负缓存 TTL 很短，
 * 且发布时会显式失效，所以不会出现「配好了但还是说没有」的长期假象。
 * <p>
 * 降级口径：
 * <ul>
 *   <li>Redis 读写异常 → 当作 miss，继续打 DB（这是设计允许的唯一降级）；</li>
 *   <li>DB 读成功即正常返回，即使 Redis 不可用；</li>
 *   <li>DB 也没有 → 返回空，由 Assembler 决定阻断出站（绝不用旧值或 classpath 文件兜底）。</li>
 * </ul>
 * <p>
 * 线程安全：Caffeine 与 StringRedisTemplate 均可并发；单飞保证同键只打一次库。
 */
@Repository
@Primary
public class CachedPromptSnapshotProvider implements PromptSnapshotProvider {

    /** 空值哨兵：区分「缓存里确认没有」与「缓存里没这条」 */
    static final String MISS = "\u0000MISS";

    private final PromptSnapshotProvider delegate;
    private final PromptCacheStore redis;
    private final ObjectMapper json;
    private final PromptCacheProperties properties;
    private final SingleFlight flight = new SingleFlight();

    /** 进程内层：值为 JSON 串或 MISS 哨兵 */
    private final Cache<String, String> local;

    /**
     * @param delegate   DB 直读实现
     * @param redis      跨进程共享层
     * @param json       快照序列化
     * @param properties TTL 与容量
     */
    public CachedPromptSnapshotProvider(@Qualifier("dbPromptSnapshotProvider") PromptSnapshotProvider delegate,
                                        PromptCacheStore redis,
                                        ObjectMapper json, PromptCacheProperties properties) {
        this.delegate = delegate;
        this.redis = redis;
        this.json = json;
        this.properties = properties;
        this.local = Caffeine.newBuilder()
                .maximumSize(Math.max(properties.getLocalMaxSize(), 16))
                .expireAfterWrite(Duration.ofSeconds(Math.max(properties.getLocalTtlSec(), 1)))
                .build();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptConstraintSnapshot> activeConstraint(String locale) {
        return load(PromptCacheKeys.constraint(locale), PromptConstraintSnapshot.class,
                () -> delegate.activeConstraint(locale));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptTemplateSnapshot> templateByKey(String templateKey, String locale, int version) {
        return load(PromptCacheKeys.templateVersion(templateKey, locale, version), PromptTemplateSnapshot.class,
                () -> delegate.templateByKey(templateKey, locale, version));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptTemplateSnapshot> templateById(Long id) {
        return load(PromptCacheKeys.templateById(id), PromptTemplateSnapshot.class,
                () -> delegate.templateById(id));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptTemplateRef> activeRefByPurpose(String purpose, String locale) {
        return load(PromptCacheKeys.purposeActive(purpose, locale), PromptTemplateRef.class,
                () -> delegate.activeRefByPurpose(purpose, locale));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PromptTemplateRef> activeRefByKey(String templateKey, String locale) {
        return load(PromptCacheKeys.templateActive(templateKey, locale), PromptTemplateRef.class,
                () -> delegate.activeRefByKey(templateKey, locale));
    }

    /**
     * 失效一个键：本地层直接作废，Redis 层删共享键。
     * 由发布方在**事务提交后**调用，否则并发读者可能把提交前的旧值写回缓存。
     *
     * @param key 缓存键
     */
    public void evict(String key) {
        local.invalidate(key);
        redis.evict(key);
    }

    /**
     * 显式置空（负缓存）：用于「确认不存在」的结果，避免下一次调用立刻又打库。
     */
    private void putMiss(String key) {
        Duration ttl = Duration.ofSeconds(Math.max(properties.getMissTtlSec(), 1));
        local.put(key, MISS);
        redis.put(key, MISS, ttl);
    }

    /**
     * 三级读取 + 单飞。
     *
     * @param key  缓存键
     * @param type 快照类型（反序列化用）
     * @param db   真正打库的加载函数
     * @param <T>  快照类型
     * @return 命中值；空表示「确认不存在」
     */
    private <T> Optional<T> load(String key, Class<T> type, Supplier<Optional<T>> db) {
        String cached = local.getIfPresent(key);
        if (cached != null) {
            return deserialize(key, type, cached);
        }
        // 单飞：同键并发只让一个线程穿透到 Redis/DB，其余等它的结果
        return flight.run(key, () -> loadThrough(key, type, db));
    }

    /**
     * 冷 key 加载：先问 Redis，再打 DB，并把结果回填两层。
     * 回填统一存「JSON 串」或「MISS 哨兵」，保证三层语义一致。
     */
    private <T> Optional<T> loadThrough(String key, Class<T> type, Supplier<Optional<T>> db) {
        Optional<String> remote = redis.get(key);
        if (remote.isPresent()) {
            String value = remote.get();
            local.put(key, value);
            return deserialize(key, type, value);
        }
        Optional<T> hit = db.get();
        if (hit.isEmpty()) {
            putMiss(key);
            return Optional.empty();
        }
        String packed;
        try {
            packed = json.writeValueAsString(hit.get());
        } catch (Exception ex) {
            // 序列化失败只影响「能不能缓存」，不影响本次返回：数据本身已经读到了
            SingleFlight.warnDegraded(key, "serialize");
            return hit;
        }
        redis.put(key, packed, Duration.ofSeconds(Math.max(properties.getRedisTtlSec(), 1)));
        local.put(key, packed);
        return hit;
    }

    /**
     * 反序列化。坏值按 miss 处理并剔除两层，避免同一个坏值反复失败。
     */
    private <T> Optional<T> deserialize(String key, Class<T> type, String packed) {
        if (packed == null) {
            return Optional.empty();
        }
        if (MISS.equals(packed)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(json.readValue(packed, type));
        } catch (Exception ex) {
            local.invalidate(key);
            redis.evict(key);
            SingleFlight.warnDegraded(key, "deserialize");
            return Optional.empty();
        }
    }
}

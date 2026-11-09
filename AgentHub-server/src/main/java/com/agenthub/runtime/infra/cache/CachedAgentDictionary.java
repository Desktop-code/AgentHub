package com.agenthub.runtime.infra.cache;

import com.agenthub.prompt.domain.service.SingleFlight;
import com.agenthub.runtime.application.DirectAgentDictionary;
import com.agenthub.runtime.domain.model.ToolSourceView;
import com.agenthub.runtime.domain.service.AgentDictCacheKeys;
import com.agenthub.runtime.domain.service.AgentDictCacheStore;
import com.agenthub.runtime.domain.service.AgentDictionaryReader;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.tool.domain.model.ToolItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 运行期字典的缓存装饰器。所属域：runtime 的 infra。
 * <p>
 * 读取路径：<b>Redis → DB</b>，命中即返回；未命中用单飞加载，避免冷 key 并发打穿 MySQL。
 * 未加进程内 L1：字典按 id 读、条数少，Redis GET 成本可忽略，而本地层会让「改完立即生效」在
 * 多实例下出现最长一整个本地 TTL 的延迟——这正是不引入本地层的理由。
 * <p>
 * 空值也缓存（短 TTL 负缓存）：绑定指向已删除的工具项/Skill 时，否则每一轮都要打库。
 * 负缓存 TTL 很短，且写入口会显式失效，所以不会出现「新建了却一直说没有」的长期假象。
 * <p>
 * 降级口径（与 prompt 域 {@code CachedPromptSnapshotProvider} 一致）：
 * <ul>
 *   <li>Redis 读写异常 → 当作 miss，继续打 DB（设计允许的唯一降级）；</li>
 *   <li>序列化失败 → 只放弃缓存，本次照常返回 DB 读到的值；</li>
 *   <li>反序列化失败 → 剔除该键并按 miss 处理，避免同一个坏值反复失败；</li>
 *   <li>DB 也没有 → 返回空，由装配方跳过该绑定（绝不用旧值兜底）。</li>
 * </ul>
 * <p>
 * 单飞复用 prompt 域 {@code SingleFlight}：它是与业务无关的进程内互斥工具，
 * 扫描报告附录 B 也明确要求新缓存复用，不要另造一套。
 * <p>
 * 线程安全：本类无状态（单飞只有内部并发 map），可单例复用。
 */
@Repository
@Primary
public class CachedAgentDictionary implements AgentDictionaryReader {

    private static final Logger log = LoggerFactory.getLogger(CachedAgentDictionary.class);

    /** 空值哨兵：区分「缓存里确认没有」与「缓存里没这条」 */
    static final String MISS = "\u0000MISS";

    private final DirectAgentDictionary delegate;
    private final AgentDictCacheStore store;
    private final ObjectMapper json;
    private final AgentDictCacheProperties properties;
    private final SingleFlight flight = new SingleFlight();

    /**
     * @param delegate   DB 直读实现（真值来源）
     * @param store      跨进程共享层
     * @param json       快照序列化，复用容器配置（含 JavaTimeModule）
     * @param properties TTL 与开关
     */
    public CachedAgentDictionary(DirectAgentDictionary delegate, AgentDictCacheStore store,
                                 ObjectMapper json, AgentDictCacheProperties properties) {
        this.delegate = delegate;
        this.store = store;
        this.json = json;
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ToolItem> toolItem(Long itemId) {
        if (bypass(itemId)) {
            return delegate.toolItem(itemId);
        }
        return load(AgentDictCacheKeys.toolItem(itemId), ToolItem.class, () -> delegate.toolItem(itemId));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ToolSourceView> toolSource(Long sourceId) {
        if (bypass(sourceId)) {
            return delegate.toolSource(sourceId);
        }
        return load(AgentDictCacheKeys.toolSource(sourceId), ToolSourceView.class,
                () -> delegate.toolSource(sourceId));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SkillDef> skill(Long skillId) {
        if (bypass(skillId)) {
            return delegate.skill(skillId);
        }
        return load(AgentDictCacheKeys.skillDef(skillId), SkillDef.class, () -> delegate.skill(skillId));
    }

    /**
     * 失效一个键。由失效器在**事务提交后**调用（见 {@code AgentDictCacheInvalidatorImpl}）。
     *
     * @param key 缓存键
     */
    public void evict(String key) {
        store.evict(key);
    }

    /**
     * 批量失效。
     *
     * @param keys 缓存键
     */
    public void evictAll(Collection<String> keys) {
        store.evictAll(keys);
    }

    /**
     * 缓存关闭或 id 非法时直接走 DB：不开缓存就不该产生任何键。
     *
     * @param id 主键
     * @return true 表示本次跳过缓存
     */
    private boolean bypass(Long id) {
        return !properties.isEnabled() || id == null || id <= 0;
    }

    /**
     * 读取缓存或加载：同一键并发只让一个线程穿透到 Redis/DB。
     *
     * @param key  缓存键
     * @param type 快照类型（反序列化用）
     * @param db   真正打库的加载函数
     * @param <T>  快照类型
     * @return 命中值；空表示「确认不存在」
     */
    private <T> Optional<T> load(String key, Class<T> type, Supplier<Optional<T>> db) {
        return flight.run(key, () -> loadThrough(key, type, db));
    }

    /**
     * 冷 key 加载：先问 Redis，再打 DB，命中则回填。
     * 回填统一存「JSON 串」或「MISS 哨兵」，保证两层语义一致。
     */
    private <T> Optional<T> loadThrough(String key, Class<T> type, Supplier<Optional<T>> db) {
        Optional<String> remote = store.get(key);
        if (remote.isPresent()) {
            return deserialize(key, type, remote.get());
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
            log.warn("运行期字典缓存序列化失败，本次不缓存 key={} type={}", key, ex.getClass().getSimpleName());
            return hit;
        }
        store.put(key, packed, Duration.ofSeconds(Math.max(properties.getRedisTtlSec(), 1)));
        return hit;
    }

    /**
     * 显式置空（负缓存）：确认不存在时也写一条短 TTL 哨兵，避免每轮打库。
     *
     * @param key 缓存键
     */
    private void putMiss(String key) {
        store.put(key, MISS, Duration.ofSeconds(Math.max(properties.getMissTtlSec(), 1)));
    }

    /**
     * 反序列化。坏值按 miss 处理并剔除该键，避免同一个坏值反复失败。
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
            store.evict(key);
            log.warn("运行期字典缓存反序列化失败，已剔除该键 key={} stage=deserialize type={}",
                    key, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}

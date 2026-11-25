package com.agenthub.runtime.infra.cache;

import com.agenthub.runtime.application.DirectAgentDictionary;
import com.agenthub.runtime.domain.model.ToolSourceView;
import com.agenthub.runtime.domain.service.AgentDictCacheKeys;
import com.agenthub.runtime.domain.service.AgentDictCacheStore;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 装配字典缓存：命中、跨实例共享、失效、负缓存、单飞、无密投影。所属域：runtime。
 * <p>
 * 用内存假 Redis 与 Mockito 假仓储，不连 Redis / MySQL；断言重点是
 * 「仓储被打了多少次」「失效后能否读到新值」「密文有没有被写进缓存」——
 * 这三条正是本刀的可执行判据。
 */
class CachedAgentDictionaryTest {

    /** 带 JavaTimeModule：ToolItem / SkillDef 含 LocalDateTime，容器里的 ObjectMapper 同样注册了它 */
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    /** 内存 Redis：可注入读失败，用于验证「缓存抖动降级到 DB」 */
    private static final class MemoryStore implements AgentDictCacheStore {
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

        @Override
        public void evictAll(Collection<String> keys) {
            keys.forEach(data::remove);
        }
    }

    /** 假仓储容器：三个仓储 + 计数调用次数 */
    private static final class Fakes {
        final ToolItemRepository items = mock(ToolItemRepository.class);
        final ToolSourceRepository sources = mock(ToolSourceRepository.class);
        final SkillDefRepository skills = mock(SkillDefRepository.class);
        final AtomicInteger itemReads = new AtomicInteger();

        Fakes(ToolItem item, ToolSource source, SkillDef skill) {
            // 用 thenAnswer 计数：直接影响「缓存是否真的挡住了库查询」的断言
            lenient().when(items.findById(1L)).thenAnswer(invocation -> {
                itemReads.incrementAndGet();
                return Optional.ofNullable(item);
            });
            lenient().when(sources.findById(2L)).thenAnswer(invocation -> Optional.ofNullable(source));
            lenient().when(skills.findById(3L)).thenAnswer(invocation -> Optional.ofNullable(skill));
        }

        DirectAgentDictionary direct() {
            return new DirectAgentDictionary(items, sources, skills);
        }
    }

    private static ToolItem item() {
        ToolItem item = new ToolItem();
        item.setId(1L);
        item.setSourceId(2L);
        item.setName("weather");
        item.setTitle("天气");
        item.setDescription("查天气");
        item.setInputSchemaJson("{\"type\":\"object\"}");
        item.setStatus(1);
        item.setCreateTime(LocalDateTime.of(2026, 1, 1, 8, 0));
        return item;
    }

    private static ToolSource source() {
        ToolSource source = new ToolSource();
        source.setId(2L);
        source.setName("内置源");
        source.setType("BUILTIN");
        source.setTransport("SSE");
        source.setBaseUrl("https://internal.example/mcp");
        // 密文必须止步于仓储：下面的用例断言它不出现在缓存值里
        source.setAuthHeaderCipher("CIPHER-SECRET-VALUE");
        source.setAuthHeaderMask("Bea****xyz");
        source.setStatus(1);
        return source;
    }

    private static SkillDef skill() {
        SkillDef skill = new SkillDef();
        skill.setId(3L);
        skill.setName("faq");
        skill.setTitle("常见问题");
        skill.setDescription("回答常见问题");
        skill.setTriggerKeywords("faq,常见问题");
        skill.setContentMd("正文 v1");
        skill.setStatus(1);
        skill.setUpdateTime(LocalDateTime.of(2026, 1, 2, 9, 30));
        return skill;
    }

    private static AgentDictCacheProperties properties() {
        AgentDictCacheProperties properties = new AgentDictCacheProperties();
        properties.setEnabled(true);
        properties.setRedisTtlSec(60);
        properties.setMissTtlSec(1);
        return properties;
    }

    private CachedAgentDictionary cache(Fakes fakes, MemoryStore store) {
        return new CachedAgentDictionary(fakes.direct(), store, json, properties());
    }

    /**
     * 同一 id 第二次读必须命中 Redis，不再打库。
     */
    @Test
    void secondReadHitsCacheWithoutTouchingDb() {
        Fakes fakes = new Fakes(item(), source(), skill());
        MemoryStore store = new MemoryStore();
        CachedAgentDictionary cache = cache(fakes, store);

        assertEquals("weather", cache.toolItem(1L).orElseThrow().getName());
        assertEquals("weather", cache.toolItem(1L).orElseThrow().getName());
        assertEquals("weather", cache.toolItem(1L).orElseThrow().getName());

        assertEquals(1, fakes.itemReads.get(), "命中缓存后不得再打 DB");
        assertTrue(store.data.containsKey(AgentDictCacheKeys.toolItem(1L)), "值应写进 Redis 键");
    }

    /**
     * 另一台实例（新的装饰器实例、共享同一 Redis）也应命中，不再打库——这是多实例共享的判据。
     */
    @Test
    void otherInstanceSharesRedis() {
        Fakes fakes = new Fakes(item(), source(), skill());
        MemoryStore store = new MemoryStore();

        cache(fakes, store).toolItem(1L);
        cache(fakes, store).toolItem(1L);

        assertEquals(1, fakes.itemReads.get(), "Redis 命中后不得再打 DB");
    }

    /**
     * 失效后必须重新读库并读到新值：不允许「改完不生效」。
     */
    @Test
    void evictReadsNewValue() {
        Fakes fakes = new Fakes(item(), source(), skill());
        MemoryStore store = new MemoryStore();
        CachedAgentDictionary cache = cache(fakes, store);

        assertEquals("天气", cache.toolItem(1L).orElseThrow().getTitle());

        // 模拟编辑：仓储换值 + 失效（真实路径里失效由写入口在事务提交后触发）
        ToolItem updated = item();
        updated.setTitle("天气查询");
        when(fakes.items.findById(1L)).thenAnswer(invocation -> {
            fakes.itemReads.incrementAndGet();
            return Optional.of(updated);
        });
        // 重新打桩时 Mockito 会先调用旧答案一次，把计数归位再断言
        fakes.itemReads.set(1);
        cache.evict(AgentDictCacheKeys.toolItem(1L));

        assertEquals("天气查询", cache.toolItem(1L).orElseThrow().getTitle());
        assertEquals(2, fakes.itemReads.get(), "失效后必须重新打库一次");
    }

    /**
     * Redis 读失败降级为 miss，继续打库并正常返回（允许的降级）。
     */
    @Test
    void redisFailureDegradesToDb() {
        Fakes fakes = new Fakes(item(), source(), skill());
        MemoryStore store = new MemoryStore();
        store.failReads = true;

        assertTrue(cache(fakes, store).toolItem(1L).isPresent());
        assertEquals(1, fakes.itemReads.get());
    }

    /**
     * 负缓存：库确认不存在时写短 TTL 哨兵，短时间内不再打库（避免脏绑定把库打穿）。
     */
    @Test
    void missingIsNegativelyCached() {
        Fakes fakes = new Fakes(null, null, null);
        MemoryStore store = new MemoryStore();
        CachedAgentDictionary cache = cache(fakes, store);

        assertTrue(cache.toolItem(1L).isEmpty());
        assertTrue(cache.toolItem(1L).isEmpty());
        assertTrue(cache.toolItem(1L).isEmpty());

        assertEquals(1, fakes.itemReads.get(), "负缓存期内不得反复打库");
        assertEquals(CachedAgentDictionary.MISS, store.data.get(AgentDictCacheKeys.toolItem(1L)));
    }

    /**
     * 工具源走无密投影：缓存值里不得出现密文、掩码与出站地址。
     */
    @Test
    void sourceProjectionCarriesNoSecret() {
        Fakes fakes = new Fakes(item(), source(), skill());
        MemoryStore store = new MemoryStore();
        CachedAgentDictionary cache = cache(fakes, store);

        ToolSourceView view = cache.toolSource(2L).orElseThrow();
        assertEquals("BUILTIN", view.getType());
        assertEquals(1, view.getStatus());

        String packed = store.data.get(AgentDictCacheKeys.toolSource(2L));
        assertFalse(packed.contains("CIPHER-SECRET-VALUE"), "密文不得进缓存");
        assertFalse(packed.contains("Bea****xyz"), "掩码也不进缓存");
        assertFalse(packed.contains("internal.example"), "出站地址不进缓存");
    }

    /**
     * Skill 快照带生效正文与触发词，反序列化后字段完整（容器 ObjectMapper 含 JavaTime）。
     */
    @Test
    void skillSnapshotRoundTrips() {
        Fakes fakes = new Fakes(item(), source(), skill());
        MemoryStore store = new MemoryStore();
        CachedAgentDictionary cache = cache(fakes, store);

        // 先用另一个实例写进 Redis，再让本实例从 Redis 反序列化
        cache(fakes, store).skill(3L);
        SkillDef cached = cache(fakes, store).skill(3L).orElseThrow();

        assertEquals("正文 v1", cached.getContentMd());
        assertEquals("faq,常见问题", cached.getTriggerKeywords());
        assertEquals(LocalDateTime.of(2026, 1, 2, 9, 30), cached.getUpdateTime());
    }

    /**
     * 缓存关闭时直读库，不产生任何键（排障开关的判据）。
     */
    @Test
    void disabledCacheBypassesRedis() {
        Fakes fakes = new Fakes(item(), source(), skill());
        MemoryStore store = new MemoryStore();
        AgentDictCacheProperties off = properties();
        off.setEnabled(false);
        CachedAgentDictionary cache = new CachedAgentDictionary(fakes.direct(), store, json, off);

        cache.toolItem(1L);
        cache.toolItem(1L);

        assertEquals(2, fakes.itemReads.get(), "关闭缓存后每次都打库");
        assertTrue(store.data.isEmpty(), "关闭缓存不得写键");
    }

    /**
     * 单飞：同一冷 key 的 8 个并发请求只应产生 1 次仓储调用（防缓存击穿）。
     */
    @Test
    void concurrentColdKeyLoadsOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolItemRepository slowItems = mock(ToolItemRepository.class);
        when(slowItems.findById(1L)).thenAnswer(invocation -> {
            calls.incrementAndGet();
            try {
                // 放大窗口，让并发线程确实同时到达
                Thread.sleep(80);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(item());
        });
        DirectAgentDictionary direct = new DirectAgentDictionary(slowItems, Mockito.mock(ToolSourceRepository.class),
                Mockito.mock(SkillDefRepository.class));
        CachedAgentDictionary cache = new CachedAgentDictionary(direct, new MemoryStore(), json, properties());

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (cache.toolItem(1L).isEmpty()) {
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
        assertEquals(1, calls.get(), "单飞应把同键并发压成 1 次仓储调用");
    }

    /**
     * 批量失效：多键一次删掉（同步/批量启停路径）。
     */
    @Test
    void evictAllRemovesSeveralKeys() {
        Fakes fakes = new Fakes(item(), source(), skill());
        MemoryStore store = new MemoryStore();
        CachedAgentDictionary cache = cache(fakes, store);

        cache.toolItem(1L);
        cache.skill(3L);
        assertEquals(2, store.data.size());

        cache.evictAll(List.of(AgentDictCacheKeys.toolItem(1L), AgentDictCacheKeys.skillDef(3L)));
        assertTrue(store.data.isEmpty());
    }
}

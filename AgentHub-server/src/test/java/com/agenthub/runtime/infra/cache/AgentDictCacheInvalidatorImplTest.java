package com.agenthub.runtime.infra.cache;

import com.agenthub.runtime.application.DirectAgentDictionary;
import com.agenthub.runtime.domain.service.AgentDictCacheKeys;
import com.agenthub.runtime.domain.service.AgentDictCacheStore;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 字典缓存失效时机：必须在事务提交后清，且删源要连带删其下工具项。所属域：runtime。
 * <p>
 * 若在事务内删键，并发读者会在提交前把旧值写回缓存，提交后再没人清——本测正是防这个。
 */
class AgentDictCacheInvalidatorImplTest {

    private final JsonMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    /** 内存 Redis：只关心键是否被删 */
    private static final class MemoryStore implements AgentDictCacheStore {
        final Map<String, String> data = new ConcurrentHashMap<>();

        @Override
        public Optional<String> get(String key) {
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

    private static ToolItem item(String title) {
        ToolItem item = new ToolItem();
        item.setId(1L);
        item.setSourceId(2L);
        item.setName("weather");
        item.setTitle(title);
        item.setStatus(1);
        return item;
    }

    private static ToolSource source() {
        ToolSource source = new ToolSource();
        source.setId(2L);
        source.setName("内置源");
        source.setType("BUILTIN");
        source.setStatus(1);
        return source;
    }

    private static AgentDictCacheProperties properties() {
        AgentDictCacheProperties properties = new AgentDictCacheProperties();
        properties.setEnabled(true);
        properties.setRedisTtlSec(60);
        properties.setMissTtlSec(1);
        return properties;
    }

    /**
     * 提交前仍读旧值，提交后才失效并读到新值。
     */
    @Test
    void evictionRunsAfterCommit() {
        ToolItemRepository items = mock(ToolItemRepository.class);
        ToolItemRepository sourceItems = mock(ToolItemRepository.class);
        ToolSourceRepository sources = mock(ToolSourceRepository.class);
        SkillDefRepository skills = mock(SkillDefRepository.class);
        ToolItem oldValue = item("旧标题");
        ToolItem newValue = item("新标题");
        when(items.findById(1L)).thenReturn(Optional.of(oldValue));

        MemoryStore store = new MemoryStore();
        CachedAgentDictionary cache = new CachedAgentDictionary(
                new DirectAgentDictionary(items, sources, skills), store, json, properties());
        AgentDictCacheInvalidatorImpl invalidator = new AgentDictCacheInvalidatorImpl(cache, sourceItems);

        // 先暖缓存
        assertEquals("旧标题", cache.toolItem(1L).orElseThrow().getTitle());

        // 模拟一个活动事务：注册回调后不立刻失效
        TransactionSynchronizationManager.initSynchronization();
        when(items.findById(1L)).thenReturn(Optional.of(newValue));
        invalidator.evictToolItem(1L);
        assertEquals("旧标题", cache.toolItem(1L).orElseThrow().getTitle(), "提交前不得提前失效");

        // 提交：手动触发 afterCommit
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertFalse(synchronizations.isEmpty());
        for (var synchronization : synchronizations) {
            synchronization.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();

        assertEquals("新标题", cache.toolItem(1L).orElseThrow().getTitle());
    }

    /**
     * 不在事务里时立即失效（启动期回填、单测直接调用）。
     */
    @Test
    void evictionRunsImmediatelyWithoutTransaction() {
        ToolItemRepository items = mock(ToolItemRepository.class);
        ToolSourceRepository sources = mock(ToolSourceRepository.class);
        SkillDefRepository skills = mock(SkillDefRepository.class);
        when(items.findById(1L)).thenReturn(Optional.of(item("旧标题")));

        MemoryStore store = new MemoryStore();
        CachedAgentDictionary cache = new CachedAgentDictionary(
                new DirectAgentDictionary(items, sources, skills), store, json, properties());
        AgentDictCacheInvalidatorImpl invalidator =
                new AgentDictCacheInvalidatorImpl(cache, mock(ToolItemRepository.class));

        cache.toolItem(1L);
        when(items.findById(1L)).thenReturn(Optional.of(item("立即刷新")));
        invalidator.evictToolItem(1L);

        assertEquals("立即刷新", cache.toolItem(1L).orElseThrow().getTitle());
    }

    /**
     * 删源/改源：源键与「源下当前未删除工具项」的键都要删掉。
     */
    @Test
    void evictSourceAlsoClearsItemsOfSource() {
        ToolItemRepository items = mock(ToolItemRepository.class);
        ToolSourceRepository sources = mock(ToolSourceRepository.class);
        SkillDefRepository skills = mock(SkillDefRepository.class);
        lenient().when(items.findById(1L)).thenReturn(Optional.of(item("标题")));
        lenient().when(sources.findById(2L)).thenReturn(Optional.of(source()));
        lenient().when(items.listBySourceId(2L)).thenReturn(List.of(item("标题")));

        MemoryStore store = new MemoryStore();
        CachedAgentDictionary cache = new CachedAgentDictionary(
                new DirectAgentDictionary(items, sources, skills), store, json, properties());
        AgentDictCacheInvalidatorImpl invalidator = new AgentDictCacheInvalidatorImpl(cache, items);

        cache.toolItem(1L);
        cache.toolSource(2L);
        assertEquals(2, store.data.size());

        invalidator.evictToolSource(2L);

        assertTrue(store.data.isEmpty(), "源键与其下工具项键都应删除");
        assertTrue(store.data.get(AgentDictCacheKeys.toolItem(1L)) == null);
    }
}

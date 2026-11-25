package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.ShortMemoryCache;
import com.agenthub.memory.infra.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 召回门面：LONG 取数委托给单一编排、Top N 与字符预算两个口径都只有一处实现。
 * 不连库、不打模型。
 */
@ExtendWith(MockitoExtension.class)
class MemoryRecallFacadeTest {

    @Mock
    private ShortMemoryCache cache;
    @Mock
    private MemoryItemRepository items;
    @Mock
    private MemoryLongRecall longRecall;

    private MemoryProperties properties;
    private MemoryRecallFacade facade;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setRecallLongTopN(5);
        properties.setRecallLongTopNCap(20);
        properties.setRecallMaxChars(4000);
        facade = new MemoryRecallFacade(cache, items, properties, longRecall);
    }

    /**
     * query 必须原样传给编排：语义召回靠它取向量，门面不能再自己决定用不用。
     */
    @Test
    void queryIsForwardedToLongRecall() {
        MemoryPolicy policy = policy(true, 100);
        when(longRecall.topN(4L, 7L, "查天气", 5)).thenReturn(List.of(item(1L, "杭州")));
        when(items.listActive(eq(4L), eq(null), eq("AGENT"), anyInt(), any())).thenReturn(List.of());

        MemoryRecallFacade.Durable durable = facade.durable(policy, 4L, 7L, "查天气");

        verify(longRecall).topN(4L, 7L, "查天气", 5);
        assertTrue(durable.pref().contains("杭州"));
        assertEquals(1, durable.items().size());
    }

    /**
     * 策略关闭长期记忆：一次编排都不该发生，也不产生补参依据。
     */
    @Test
    void longDisabledSkipsLongRecall() {
        MemoryPolicy policy = policy(false, 100);
        when(items.listActive(eq(4L), eq(null), eq("AGENT"), anyInt(), any())).thenReturn(List.of());

        MemoryRecallFacade.Durable durable = facade.durable(policy, 4L, 7L, "查天气");

        assertEquals("", durable.pref());
        assertTrue(durable.items().isEmpty());
        verify(longRecall, org.mockito.Mockito.never()).topN(anyLong(), anyLong(), any(), anyInt());
    }

    /**
     * Top N 取「平台默认」与「策略上限」的较小值：策略配大不能突破平台硬顶。
     */
    @Test
    void topNUsesPlatformCapNotPolicyMax() {
        MemoryPolicy policy = policy(true, 1);
        when(longRecall.topN(4L, 7L, "查天气", 1)).thenReturn(List.of(item(1L, "杭州")));
        when(items.listActive(eq(4L), eq(null), eq("AGENT"), anyInt(), any())).thenReturn(List.of());

        facade.durable(policy, 4L, 7L, "查天气");

        verify(longRecall).topN(4L, 7L, "查天气", 1);
    }

    /**
     * 字符预算沿用现网口径：超预算的条目不再注入，但补参依据只含真正注入的行。
     */
    @Test
    void charBudgetCutsInjectedItemsAndKeepsFactsConsistent() {
        properties.setRecallMaxChars(15);
        MemoryPolicy policy = policy(true, 100);
        when(longRecall.topN(eq(4L), eq(7L), any(), anyInt())).thenReturn(List.of(
                item(1L, "一二三四五六七八九十"), item(2L, "一二三四五六七八九十")));
        when(items.listActive(eq(4L), eq(null), eq("AGENT"), anyInt(), any())).thenReturn(List.of());

        MemoryRecallFacade.Durable durable = facade.durable(policy, 4L, 7L, "查天气");

        // 第一条 10 字已占一半预算，第二条再加会超 → 只注入一条，补参依据与注入同源
        assertEquals(1, durable.items().size());
        assertEquals(1L, durable.items().get(0).getId());
        assertEquals("- 一二三四五六七八九十", durable.pref());
    }

    /**
     * 兼容入口（无 query）等价于不做语义召回，仍应走到编排的 MySQL 降级分支。
     */
    @Test
    void legacyEntryDelegatesWithNullQuery() {
        MemoryPolicy policy = policy(true, 100);
        when(longRecall.topN(4L, 7L, null, 5)).thenReturn(List.of());
        when(items.listActive(eq(4L), eq(null), eq("AGENT"), anyInt(), any())).thenReturn(new ArrayList<>());

        facade.durable(policy, 4L, 7L);

        verify(longRecall).topN(4L, 7L, null, 5);
    }

    private static MemoryPolicy policy(boolean longEnabled, int maxLongItems) {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(longEnabled ? 1 : 0);
        policy.setMaxLongItems(maxLongItems);
        return policy;
    }

    private static MemoryItem item(long id, String content) {
        MemoryItem item = new MemoryItem();
        item.setId(id);
        item.setContent(content);
        return item;
    }
}

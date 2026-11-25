package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryVectorHit;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.spi.rag.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 向量召回出站：未就绪 / 空 query / 维度不符 / 失败一律空列表且不抛；实例 9；查询向量归一化。
 * 网关用 mock 覆盖超时与拒绝（真实网关的等待逻辑在 MemoryRecallGatewayTest 里用本地线程池验证）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorRecallTest {

    private static final String COLLECTION = "agenthub_memory_long";
    private static final int DIMENSION = 4;
    /** 平台级 Embedding 实例 id（仅单测夹具；生产由模型中心平台锁定解析） */
    private static final long EMBEDDING_ID = 9L;
    /** 平台级 Rerank 实例 id（仅单测夹具） */
    private static final long RERANK_ID = 12L;

    @Mock
    private MemoryVectorSupport support;
    @Mock
    private EmbeddingClient embeddings;
    @Mock
    private MemoryVectorStore store;
    @Mock
    private MemoryRecallGateway gateway;

    private MemoryProperties properties;
    private MemoryVectorRecall recall;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setRecallVectorTimeoutMs(800L);
        properties.setRecallVectorMaxAttempts(1);
        recall = new MemoryVectorRecall(support, embeddings, store, gateway, properties);
    }

    @Test
    void notReadyReturnsEmptyWithoutOutbound() {
        when(support.readiness()).thenReturn(MemoryVectorReadiness.notReady(COLLECTION, DIMENSION, "milvus down"));

        assertTrue(recall.candidates(4L, 7L, "查天气", 10).isEmpty());
        verifyNoInteractions(gateway);
    }

    @Test
    void readinessExceptionIsTreatedAsNotReady() {
        when(support.readiness()).thenThrow(new IllegalStateException("boom"));

        assertTrue(recall.candidates(4L, 7L, "查天气", 10).isEmpty());
        verifyNoInteractions(gateway);
    }

    @Test
    void blankQueryDoesNotEmbed() {
        assertTrue(recall.candidates(4L, 7L, "   ", 10).isEmpty());

        verifyNoInteractions(support, gateway);
    }

    @Test
    void embedFailureReturnsEmpty() {
        when(support.readiness()).thenReturn(ready());
        when(gateway.call(eq("embed"), anyLong(), any())).thenReturn(null);

        assertTrue(recall.candidates(4L, 7L, "查天气", 10).isEmpty());
        verify(store, never()).search(any(), any(), any(), any(), anyInt());
    }

    @Test
    void usesInstanceNineAndNormalizesQueryVector() {
        when(support.readiness()).thenReturn(ready());
        // 网关是 mock：直接执行传入的出站动作，保证断言的参数是真实传下去的
        when(gateway.call(eq("embed"), anyLong(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(2);
            return supplier.get();
        });
        when(gateway.call(eq("search"), anyLong(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(2);
            return supplier.get();
        });
        when(embeddings.embedOne(EMBEDDING_ID, "查天气", DIMENSION))
                .thenReturn(new float[]{3f, 4f, 0f, 0f});
        when(store.search(eq(COLLECTION), eq(7L), eq(4L), any(), eq(10)))
                .thenReturn(List.of(new MemoryVectorHit(1L, 0.9f)));

        List<MemoryVectorHit> hits = recall.candidates(4L, 7L, "查天气", 10);

        assertEquals(1, hits.size());
        org.mockito.ArgumentCaptor<float[]> captor = org.mockito.ArgumentCaptor.forClass(float[].class);
        verify(store).search(eq(COLLECTION), eq(7L), eq(4L), captor.capture(), eq(10));
        assertEquals(0.6f, captor.getValue()[0], 1e-6);
        assertEquals(0.8f, captor.getValue()[1], 1e-6);
    }

    @Test
    void dimensionMismatchSkipsSearch() {
        when(support.readiness()).thenReturn(ready());
        when(gateway.call(eq("embed"), anyLong(), any())).thenReturn(new float[]{1f, 2f});

        assertTrue(recall.candidates(4L, 7L, "查天气", 10).isEmpty());
        verify(store, never()).search(any(), any(), any(), any(), anyInt());
    }

    @Test
    void retriesAtMostOnceWhenConfigured() {
        properties.setRecallVectorMaxAttempts(2);
        when(support.readiness()).thenReturn(ready());
        when(gateway.call(eq("embed"), anyLong(), any())).thenReturn(null);

        assertTrue(recall.candidates(4L, 7L, "查天气", 10).isEmpty());
        // 规格：单轮重试 ≤1，因此 embed 最多两次
        verify(gateway, times(2)).call(eq("embed"), anyLong(), any());
    }

    @Test
    void attemptsAreCappedAtTwo() {
        properties.setRecallVectorMaxAttempts(9);
        when(support.readiness()).thenReturn(ready());
        when(gateway.call(eq("embed"), anyLong(), any())).thenReturn(null);

        recall.candidates(4L, 7L, "查天气", 10);

        verify(gateway, times(2)).call(eq("embed"), anyLong(), any());
    }

    @Test
    void longQueryIsClipped() {
        when(support.readiness()).thenReturn(ready());
        when(gateway.call(eq("embed"), anyLong(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(2);
            return supplier.get();
        });
        char[] big = new char[MemoryVectorRecall.MAX_QUERY_CHARS + 50];
        java.util.Arrays.fill(big, '问');
        when(embeddings.embedOne(eq(EMBEDDING_ID),
                eq(new String(big, 0, MemoryVectorRecall.MAX_QUERY_CHARS)), eq(DIMENSION)))
                .thenReturn(new float[]{1f, 0f, 0f, 0f});
        when(gateway.call(eq("search"), anyLong(), any())).thenReturn(List.of());

        recall.candidates(4L, 7L, new String(big), 10);

        verify(embeddings).embedOne(eq(EMBEDDING_ID),
                eq(new String(big, 0, MemoryVectorRecall.MAX_QUERY_CHARS)), eq(DIMENSION));
    }

    @Test
    void emptyHitsIsEmptyList() {
        when(support.readiness()).thenReturn(ready());
        when(gateway.call(eq("embed"), anyLong(), any())).thenReturn(new float[]{1f, 0f, 0f, 0f});
        when(gateway.call(eq("search"), anyLong(), any())).thenReturn(List.of());

        assertTrue(recall.candidates(4L, 7L, "查天气", 10).isEmpty());
    }

    @Test
    void storeExceptionFromRealSupplierIsSwallowedByGatewayMock() {
        when(support.readiness()).thenReturn(ready());
        when(gateway.call(eq("embed"), anyLong(), any())).thenReturn(new float[]{1f, 0f, 0f, 0f});
        // 网关在真实实现里会把异常收敛成 null；这里直接模拟收敛结果
        when(gateway.call(eq("search"), anyLong(), any())).thenReturn(null);

        assertTrue(recall.candidates(4L, 7L, "查天气", 10).isEmpty());
    }

    private static MemoryVectorReadiness ready() {
        return MemoryVectorReadiness.ready(COLLECTION, DIMENSION, EMBEDDING_ID,
                RERANK_ID);
    }
}

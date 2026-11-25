package com.agenthub.memory.infra.milvus;

import com.agenthub.memory.domain.model.MemoryVectorHit;
import com.agenthub.memory.domain.model.MemoryVectorRow;
import com.agenthub.memory.domain.service.MemoryVectorSchema;
import com.agenthub.memory.domain.service.MemoryVectorStoreException;
import com.agenthub.rag.infra.milvus.MilvusClientHolder;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 记忆集合 ensure / upsert / search：维度只来自入参、已存在不重建但必须校验字段、
 * 写入带齐 expired_time/importance/content、检索带未过期过滤并回传正文副本。
 * 全部 mock Milvus SDK，不发起任何真实 gRPC 连接。
 */
@ExtendWith(MockitoExtension.class)
class MilvusMemoryVectorStoreTest {

    private static final String COLLECTION = "agenthub_memory_long";
    private static final int DIMENSION = 768;

    @Mock
    private MilvusClientHolder holder;
    @Mock
    private MilvusClientV2 client;

    private MilvusMemoryVectorStore store;

    @BeforeEach
    void setUp() {
        store = new MilvusMemoryVectorStore(holder, 15000L);
    }

    @Test
    void createsCollectionWithDimensionFromCallerAndLoadsIt() {
        when(holder.client()).thenReturn(client);
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(false);
        when(client.getLoadState(any(GetLoadStateReq.class))).thenReturn(false);

        store.ensureCollection(COLLECTION, DIMENSION);

        ArgumentCaptor<CreateCollectionReq> captor = ArgumentCaptor.forClass(CreateCollectionReq.class);
        verify(client).createCollection(captor.capture());
        CreateCollectionReq request = captor.getValue();
        assertEquals(COLLECTION, request.getCollectionName());
        assertFalse(request.getCollectionSchema().isEnableDynamicField());

        CreateCollectionReq.FieldSchema embedding = fieldOf(request, MemoryVectorSchema.FIELD_EMBEDDING);
        // 维度必须与调用方（YAML）一致，实现里不得出现第二套默认维度
        assertEquals(DIMENSION, embedding.getDimension());
        assertEquals(DataType.FloatVector, embedding.getDataType());
        // 刀7：建集必须一次带齐三列，避免「以后再一点点加」触发重建
        assertEquals(DataType.Int64, fieldOf(request, MemoryVectorSchema.FIELD_EXPIRED_TIME).getDataType());
        assertEquals(DataType.Int64, fieldOf(request, MemoryVectorSchema.FIELD_IMPORTANCE).getDataType());
        CreateCollectionReq.FieldSchema content = fieldOf(request, MemoryVectorSchema.FIELD_CONTENT);
        assertEquals(DataType.VarChar, content.getDataType());
        assertEquals(MemoryVectorSchema.CONTENT_MAX_LENGTH, content.getMaxLength());
        // 建完必须同步加载，否则检索/写入会报集合未加载
        verify(client).loadCollection(any(LoadCollectionReq.class));
    }

    @Test
    void existingCollectionIsNotRecreatedOnRepeatedEnsure() {
        when(holder.client()).thenReturn(client);
        // 第一次不存在 → 建；第二次已存在 → 只校验 + 加载，绝不重建
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(false, true);
        when(client.getLoadState(any(GetLoadStateReq.class))).thenReturn(true);
        when(client.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(describedWith(MemoryVectorSchema.fields(DIMENSION)));

        store.ensureCollection(COLLECTION, DIMENSION);
        store.ensureCollection(COLLECTION, DIMENSION);

        verify(client, times(1)).createCollection(any());
        verify(client, never()).loadCollection(any());
    }

    @Test
    void existingCollectionWithDifferentDimensionFailsFast() {
        when(holder.client()).thenReturn(client);
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(client.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(describedWith(MemoryVectorSchema.fields(512)));

        MemoryVectorStoreException error = assertThrows(MemoryVectorStoreException.class,
                () -> store.ensureCollection(COLLECTION, DIMENSION));

        assertTrue(error.getMessage().contains("维度"));
        verify(client, never()).createCollection(any());
    }

    @Test
    void existingCollectionMissingKnifeSevenFieldsFailsWithRebuildHint() {
        when(holder.client()).thenReturn(client);
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        // 旧集合：只有刀1 的五个字段，没有 expired_time/importance/content
        when(client.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(describedWith(legacyFields(DIMENSION)));

        MemoryVectorStoreException error = assertThrows(MemoryVectorStoreException.class,
                () -> store.ensureCollection(COLLECTION, DIMENSION));

        // 必须点名缺哪些列，且不得自动重建（生产数据不能由代码静默 drop）
        assertTrue(error.getMessage().contains(MemoryVectorSchema.FIELD_EXPIRED_TIME));
        assertTrue(error.getMessage().contains(MemoryVectorSchema.FIELD_IMPORTANCE));
        assertTrue(error.getMessage().contains(MemoryVectorSchema.FIELD_CONTENT));
        assertTrue(error.getMessage().contains("重建"));
        verify(client, never()).createCollection(any());
        verify(client, never()).loadCollection(any());
    }

    @Test
    void describeFailureDoesNotBlockLoading() {
        when(holder.client()).thenReturn(client);
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(client.describeCollection(any(DescribeCollectionReq.class)))
                .thenThrow(new RuntimeException("describe timeout"));
        when(client.getLoadState(any(GetLoadStateReq.class))).thenReturn(true);

        // 读不到 schema 时只记日志放过：老版本 SDK/权限不足不应让整个记忆向量能力瘫痪
        store.ensureCollection(COLLECTION, DIMENSION);
    }

    @Test
    void connectionFailureIsWrappedWithoutExposingCauseDetails() {
        when(holder.client()).thenThrow(new IllegalStateException("未配置 agenthub.rag.milvus.uri"));

        MemoryVectorStoreException error = assertThrows(MemoryVectorStoreException.class,
                () -> store.ensureCollection(COLLECTION, DIMENSION));

        assertTrue(error.getMessage().contains("连接失败"));
    }

    @Test
    void createFailureIsWrappedAsStoreException() {
        when(holder.client()).thenReturn(client);
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(false);
        doThrow(new RuntimeException("transport error")).when(client).createCollection(any());

        MemoryVectorStoreException error = assertThrows(MemoryVectorStoreException.class,
                () -> store.ensureCollection(COLLECTION, DIMENSION));

        assertTrue(error.getMessage().contains("初始化失败"));
    }

    @Test
    void invalidArgumentsFailBeforeConnecting() {
        assertThrows(MemoryVectorStoreException.class, () -> store.ensureCollection("  ", DIMENSION));
        assertThrows(MemoryVectorStoreException.class, () -> store.ensureCollection(COLLECTION, 0));

        // 参数非法不得触发建连，避免无意义的 5 秒超时
        verifyNoInteractions(holder);
    }

    @Test
    void upsertWritesMemoryScalarsAndVector() {
        when(holder.client()).thenReturn(client);

        store.upsert(COLLECTION, new MemoryVectorRow(11L, 2L, 1L, "residence_city",
                new float[]{0.6f, 0.8f, 0f, 0f}, 1_900_000_000_000L, 80, "用户住广州"));

        ArgumentCaptor<UpsertReq> captor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(client).upsert(captor.capture());
        assertEquals(COLLECTION, captor.getValue().getCollectionName());
        JsonObject row = captor.getValue().getData().get(0);
        // 主键取 MySQL memory_item.id，保证 upsert 幂等与召回可回查
        assertEquals(11L, row.get(MemoryVectorSchema.FIELD_MEMORY_ID).getAsLong());
        assertEquals(2L, row.get(MemoryVectorSchema.FIELD_USER_ID).getAsLong());
        assertEquals(1L, row.get(MemoryVectorSchema.FIELD_AGENT_ID).getAsLong());
        assertEquals("residence_city", row.get(MemoryVectorSchema.FIELD_FACT_KEY).getAsString());
        assertEquals(0, row.get(MemoryVectorSchema.FIELD_DELETED).getAsInt());
        // 刀7 三列：过期时间用毫秒+哨兵、重要度与 MySQL 同域、正文副本截断后落库
        assertEquals(1_900_000_000_000L, row.get(MemoryVectorSchema.FIELD_EXPIRED_TIME).getAsLong());
        assertEquals(80, row.get(MemoryVectorSchema.FIELD_IMPORTANCE).getAsInt());
        assertEquals("用户住广州", row.get(MemoryVectorSchema.FIELD_CONTENT).getAsString());
        assertEquals(4, row.getAsJsonArray(MemoryVectorSchema.FIELD_EMBEDDING).size());
    }

    @Test
    void upsertFallsBackToSentinelsForNullScalars() {
        when(holder.client()).thenReturn(client);

        store.upsert(COLLECTION, new MemoryVectorRow(11L, null, null, null,
                new float[]{1f, 0f}, MemoryVectorSchema.NO_EXPIRY_MILLIS, 0, null));

        ArgumentCaptor<UpsertReq> captor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(client).upsert(captor.capture());
        JsonObject row = captor.getValue().getData().get(0);
        // 无过期写哨兵 0；null 正文写空串——Int64/VarChar 都不接受 null
        assertEquals(MemoryVectorSchema.NO_EXPIRY_MILLIS,
                row.get(MemoryVectorSchema.FIELD_EXPIRED_TIME).getAsLong());
        assertEquals("", row.get(MemoryVectorSchema.FIELD_CONTENT).getAsString());
        assertEquals(0L, row.get(MemoryVectorSchema.FIELD_USER_ID).getAsLong());
        assertEquals("", row.get(MemoryVectorSchema.FIELD_FACT_KEY).getAsString());
    }

    @Test
    void upsertWritesContentClippedToSchemaLimit() {
        when(holder.client()).thenReturn(client);
        String longText = "记".repeat(MemoryVectorSchema.CONTENT_MAX_LENGTH + 500);

        store.upsert(COLLECTION, new MemoryVectorRow(11L, 2L, 1L, null,
                new float[]{1f, 0f}, MemoryVectorSchema.NO_EXPIRY_MILLIS, 10, longText));

        ArgumentCaptor<UpsertReq> captor = ArgumentCaptor.forClass(UpsertReq.class);
        verify(client).upsert(captor.capture());
        String written = captor.getValue().getData().get(0)
                .get(MemoryVectorSchema.FIELD_CONTENT).getAsString();
        // 超长必须截断，否则 VarChar(2000) 写入会被 Milvus 整条拒绝
        assertEquals(MemoryVectorSchema.CONTENT_MAX_LENGTH, written.length());
    }

    @Test
    void upsertRejectsBlankVector() {
        MemoryVectorStoreException error = assertThrows(MemoryVectorStoreException.class,
                () -> store.upsert(COLLECTION, new MemoryVectorRow(11L, null, null, null,
                        new float[0], MemoryVectorSchema.NO_EXPIRY_MILLIS, 0, null)));

        assertTrue(error.getMessage().contains("向量为空"));
        verifyNoInteractions(holder);
    }

    @Test
    void removeDeletesByDistinctPositiveIds() {
        when(holder.client()).thenReturn(client);

        store.remove(COLLECTION, java.util.Arrays.asList(5L, 5L, null, 0L, 6L));

        ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(captor.capture());
        assertEquals(List.of(5L, 6L), captor.getValue().getIds());
    }

    @Test
    void removeWithNoValidIdsSkipsMilvus() {
        store.remove(COLLECTION, List.of());

        verifyNoInteractions(holder, client);
    }

    @Test
    void searchFiltersUserAndAgentAndReturnsScoredIds() {
        when(holder.client()).thenReturn(client);
        SearchResp resp = SearchResp.builder()
                .searchResults(List.of(List.of(
                        SearchResp.SearchResult.builder().id(11L).score(0.93f).build(),
                        SearchResp.SearchResult.builder().id(12L).score(0.71f).build())))
                .build();
        when(client.search(any(SearchReq.class))).thenReturn(resp);

        List<MemoryVectorHit> hits = store.search(COLLECTION, 2L, 1L, new float[]{0.6f, 0.8f}, 10);

        ArgumentCaptor<SearchReq> captor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(captor.capture());
        SearchReq request = captor.getValue();
        assertEquals(COLLECTION, request.getCollectionName());
        assertEquals(10, request.getTopK());
        // 召回必须同时按 user + agent 隔离，并排除已失效、已过期行
        assertTrue(request.getFilter().contains("user_id == 2"), request.getFilter());
        assertTrue(request.getFilter().contains("agent_id == 1"), request.getFilter());
        assertTrue(request.getFilter().contains("deleted == 0"), request.getFilter());
        assertTrue(request.getFilter().contains("expired_time == 0 || expired_time >"), request.getFilter());
        // 正文副本必须回传，否则「只靠主键拿正文」仍是唯一路径
        assertTrue(request.getOutputFields().contains(MemoryVectorSchema.FIELD_CONTENT));
        assertTrue(request.getOutputFields().contains(MemoryVectorSchema.FIELD_IMPORTANCE));
        assertEquals(2, hits.size());
        assertEquals(11L, hits.get(0).memoryId());
        assertEquals(0.93f, hits.get(0).score(), 1e-6);
        assertEquals(12L, hits.get(1).memoryId());
    }

    @Test
    void searchReturnsVectorSideContentAndImportance() {
        when(holder.client()).thenReturn(client);
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put(MemoryVectorSchema.FIELD_CONTENT, "用户住广州");
        entity.put(MemoryVectorSchema.FIELD_IMPORTANCE, 80L);
        entity.put(MemoryVectorSchema.FIELD_EXPIRED_TIME, 1_900_000_000_000L);
        SearchResp resp = SearchResp.builder()
                .searchResults(List.of(List.of(
                        SearchResp.SearchResult.builder().id(11L).score(0.9f).entity(entity).build())))
                .build();
        when(client.search(any(SearchReq.class))).thenReturn(resp);

        List<MemoryVectorHit> hits = store.search(COLLECTION, 2L, 1L, new float[]{1f}, 5);

        assertEquals(1, hits.size());
        MemoryVectorHit hit = hits.get(0);
        // 命中直接带正文/重要度/过期时间，供召回侧在 MySQL 正文为空时兜底
        assertEquals("用户住广州", hit.content());
        assertTrue(hit.hasContent());
        assertEquals(80, hit.importance());
        assertEquals(1_900_000_000_000L, hit.expiredTime());
    }

    @Test
    void searchRejectsEmptyQueryVector() {
        MemoryVectorStoreException error = assertThrows(MemoryVectorStoreException.class,
                () -> store.search(COLLECTION, 2L, 1L, new float[0], 10));

        assertTrue(error.getMessage().contains("查询向量为空"));
        verifyNoInteractions(holder);
    }

    @Test
    void searchFailureIsWrapped() {
        when(holder.client()).thenReturn(client);
        when(client.search(any(SearchReq.class))).thenThrow(new RuntimeException("collection not loaded"));

        MemoryVectorStoreException error = assertThrows(MemoryVectorStoreException.class,
                () -> store.search(COLLECTION, 2L, 1L, new float[]{1f}, 10));

        assertTrue(error.getMessage().contains("检索失败"));
    }

    @Test
    void embeddingDimensionReadsExistingCollectionSchema() {
        when(holder.client()).thenReturn(client);
        when(client.describeCollection(any(DescribeCollectionReq.class)))
                .thenReturn(describedWith(MemoryVectorSchema.fields(512)));

        assertEquals(512, store.embeddingDimension(COLLECTION));
    }

    @Test
    void embeddingDimensionIsNullWhenDescribeFails() {
        when(holder.client()).thenReturn(client);
        when(client.describeCollection(any(DescribeCollectionReq.class)))
                .thenThrow(new RuntimeException("describe timeout"));

        // 读不到维度只影响「冲突可观测」，不得把就绪判定变成异常
        org.junit.jupiter.api.Assertions.assertNull(store.embeddingDimension(COLLECTION));
    }

    @Test
    void dropCollectionRejectsNamesOutsideMemoryNamespace() {
        // 记忆重建绝不能误删 RAG 的 agenthub_kb_*：名字不合规在出站前就拦住
        MemoryVectorStoreException error = assertThrows(MemoryVectorStoreException.class,
                () -> store.dropCollection("agenthub_kb_1"));

        assertTrue(error.getMessage().contains("记忆命名空间"));
        verifyNoInteractions(holder, client);
    }

    @Test
    void dropCollectionIsIdempotentWhenCollectionMissing() {
        when(holder.client()).thenReturn(client);
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(false);

        store.dropCollection(COLLECTION);

        // 集不存在视为成功（重建可重复执行）
        verify(client, never()).dropCollection(any());
    }

    @Test
    void dropCollectionDropsConfiguredMemoryCollection() {
        when(holder.client()).thenReturn(client);
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(true);

        store.dropCollection(COLLECTION);

        verify(client).dropCollection(any(DropCollectionReq.class));
    }

    /**
     * 刀1 的旧字段集（不含刀7 三列），用于验证「缺列必须报错而不是重建」。
     */
    private static List<CreateCollectionReq.FieldSchema> legacyFields(int dimension) {
        List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
        fields.add(MemoryVectorSchema.primaryKeyField());
        fields.add(MemoryVectorSchema.vectorField(dimension));
        fields.add(int64(MemoryVectorSchema.FIELD_USER_ID));
        fields.add(int64(MemoryVectorSchema.FIELD_AGENT_ID));
        fields.add(CreateCollectionReq.FieldSchema.builder()
                .name(MemoryVectorSchema.FIELD_FACT_KEY).dataType(DataType.VarChar)
                .maxLength(MemoryVectorSchema.FACT_KEY_MAX_LENGTH).build());
        fields.add(CreateCollectionReq.FieldSchema.builder()
                .name(MemoryVectorSchema.FIELD_DELETED).dataType(DataType.Int8).build());
        return fields;
    }

    private static CreateCollectionReq.FieldSchema int64(String name) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(DataType.Int64).build();
    }

    private static CreateCollectionReq.FieldSchema fieldOf(CreateCollectionReq request, String name) {
        return request.getCollectionSchema().getFieldSchemaList().stream()
                .filter(field -> name.equals(field.getName()))
                .findFirst()
                .orElseThrow();
    }

    private static DescribeCollectionResp describedWith(List<CreateCollectionReq.FieldSchema> fields) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(Boolean.FALSE)
                .fieldSchemaList(fields)
                .build();
        return DescribeCollectionResp.builder().collectionSchema(schema).build();
    }
}

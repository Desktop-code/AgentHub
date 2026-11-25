package com.agenthub.memory.domain.service;

import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆向量 schema 模板：字段集合、过期哨兵与过滤表达式、正文截断、缺列检测。
 * 不依赖 Milvus 与 Spring，纯静态口径校验。
 */
class MemoryVectorSchemaTest {

    @Test
    void fieldsCoverAllKnifeSevenColumns() {
        List<CreateCollectionReq.FieldSchema> fields = MemoryVectorSchema.fields(768);
        List<String> names = fields.stream()
                .map(CreateCollectionReq.FieldSchema::getName)
                .collect(Collectors.toList());

        assertEquals(List.of(
                MemoryVectorSchema.FIELD_MEMORY_ID,
                MemoryVectorSchema.FIELD_EMBEDDING,
                MemoryVectorSchema.FIELD_USER_ID,
                MemoryVectorSchema.FIELD_AGENT_ID,
                MemoryVectorSchema.FIELD_FACT_KEY,
                MemoryVectorSchema.FIELD_DELETED,
                MemoryVectorSchema.FIELD_EXPIRED_TIME,
                MemoryVectorSchema.FIELD_IMPORTANCE,
                MemoryVectorSchema.FIELD_CONTENT), names);
        // 主键必须非自增：upsert 幂等靠调用方传 memory_id
        assertEquals(Boolean.FALSE, fields.get(0).getAutoID());
        assertEquals(DataType.VarChar, fields.get(8).getDataType());
        assertEquals(MemoryVectorSchema.CONTENT_MAX_LENGTH, fields.get(8).getMaxLength());
    }

    @Test
    void expiresAtUsesMillisAndZeroSentinel() {
        // null → 哨兵 0（无过期）
        assertEquals(MemoryVectorSchema.NO_EXPIRY_MILLIS,
                MemoryVectorSchema.toEpochMillis(null));
        // 正常时间按 Asia/Shanghai 换算成毫秒，毫秒精度不许丢（2030-01-01T00:00:00.123+08:00）
        LocalDateTime time = LocalDateTime.of(2030, 1, 1, 0, 0, 0, 123_000_000);
        assertEquals(1_893_427_200_123L, MemoryVectorSchema.toEpochMillis(time));
        // 更晚的时间一定更大，保证过滤「> nowMs」单调
        assertTrue(MemoryVectorSchema.toEpochMillis(time)
                > MemoryVectorSchema.toEpochMillis(time.minusDays(1)));
    }

    @Test
    void searchFilterPinsUserAgentDeletedAndExpiry() {
        String filter = MemoryVectorSchema.searchFilter(7L, 4L, 1_700_000_000_000L);

        assertTrue(filter.contains("user_id == 7"), filter);
        assertTrue(filter.contains("agent_id == 4"), filter);
        assertTrue(filter.contains("deleted == 0"), filter);
        // 过期条件写死：哨兵 0 或 严格大于当前毫秒（等于当前毫秒视为已过期）
        assertTrue(filter.contains("(expired_time == 0 || expired_time > 1700000000000)"), filter);
        assertTrue(filter.contains("&&"), filter);
    }

    @Test
    void searchFilterTreatsNullDimensionAsZero() {
        String filter = MemoryVectorSchema.searchFilter(0L, 0L, 1L);

        assertTrue(filter.contains("user_id == 0"), filter);
        assertTrue(filter.contains("agent_id == 0"), filter);
    }

    @Test
    void clipContentHandlesNullAndOverflow() {
        assertEquals("", MemoryVectorSchema.clipContent(null));
        assertEquals("短正文", MemoryVectorSchema.clipContent("短正文"));

        String longText = "记".repeat(MemoryVectorSchema.CONTENT_MAX_LENGTH + 10);
        String clipped = MemoryVectorSchema.clipContent(longText);
        // 必须截断到上限：超长会被 Milvus VarChar 校验整条拒绝
        assertEquals(MemoryVectorSchema.CONTENT_MAX_LENGTH, clipped.length());
        assertEquals(longText.substring(0, MemoryVectorSchema.CONTENT_MAX_LENGTH), clipped);
    }

    @Test
    void missingFieldsDetectsLegacyCollection() {
        // 读不到字段名时返回空：交调用方决定策略（现有实现选择放过，不阻断加载）
        assertTrue(MemoryVectorSchema.missingFields(null).isEmpty());
        assertTrue(MemoryVectorSchema.missingFields(List.of()).isEmpty());

        List<String> missing = MemoryVectorSchema.missingFields(
                List.of(MemoryVectorSchema.FIELD_EMBEDDING, MemoryVectorSchema.FIELD_USER_ID));
        assertTrue(missing.contains(MemoryVectorSchema.FIELD_EXPIRED_TIME));
        assertTrue(missing.contains(MemoryVectorSchema.FIELD_IMPORTANCE));
        assertTrue(missing.contains(MemoryVectorSchema.FIELD_CONTENT));

        List<String> complete = MemoryVectorSchema.fields(768).stream()
                .map(CreateCollectionReq.FieldSchema::getName)
                .collect(Collectors.toList());
        assertFalse(MemoryVectorSchema.requiredFieldNames().isEmpty());
        assertTrue(MemoryVectorSchema.missingFields(complete).isEmpty());
    }
}

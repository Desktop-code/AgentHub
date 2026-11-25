package com.agenthub.memory.infra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YAML 键绑定：确认 agenthub.memory.milvus-collection / embedding-dimension 真正落到 MemoryProperties，
 * 而不是只在 yml 里写了却没人读。默认值同时锁死为记忆专用集合与 768 维。
 */
class MemoryPropertiesBindingTest {

    @Test
    void bindsMemoryMilvusKeys() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "agenthub.memory.milvus-collection", "agenthub_memory_long",
                "agenthub.memory.embedding-dimension", "768"));

        MemoryProperties properties = new Binder(source)
                .bind("agenthub.memory", Bindable.of(MemoryProperties.class))
                .orElseGet(MemoryProperties::new);

        assertEquals("agenthub_memory_long", properties.getMilvusCollection());
        assertEquals(768, properties.getEmbeddingDimension());
    }

    @Test
    void defaultsKeepMemoryCollectionSeparatedFromRag() {
        MemoryProperties properties = new MemoryProperties();

        // 默认必须落在 agenthub_memory_ 命名空间，不能与 RAG 的 agenthub_kb_* 混用
        assertEquals("agenthub_memory_long", properties.getMilvusCollection());
        assertEquals(768, properties.getEmbeddingDimension());
    }

    /**
     * 刀9 三个新键必须真正绑定：importance 下限、去重开关、去重阈值。
     */
    @Test
    void bindsRecallImportanceAndNearDupKeys() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "agenthub.memory.recall-importance-min", "50",
                "agenthub.memory.recall-near-dup-enabled", "true",
                "agenthub.memory.recall-near-dup-similarity", "0.88"));

        MemoryProperties properties = new Binder(source)
                .bind("agenthub.memory", Bindable.of(MemoryProperties.class))
                .orElseGet(MemoryProperties::new);

        assertEquals(50, properties.recallImportanceMin());
        assertTrue(properties.isRecallNearDupEnabled());
        assertEquals(0.88d, properties.recallNearDupSimilarity(), 1e-9);
    }

    /**
     * 默认值锁死：下限 0（不过滤）、去重关闭、阈值 0.92。
     */
    @Test
    void recallDefaultsDoNotFilterOrDedup() {
        MemoryProperties properties = new MemoryProperties();

        assertEquals(0, properties.recallImportanceMin());
        assertFalse(properties.isRecallNearDupEnabled());
        assertEquals(0.92d, properties.recallNearDupSimilarity(), 1e-9);
    }

    /**
     * 越界钳制：importance 压到 100，similarity 压到 1。
     */
    @Test
    void recallThresholdsAreClamped() {
        MemoryProperties properties = new MemoryProperties();
        properties.setRecallImportanceMin(150);
        properties.setRecallNearDupSimilarity(1.5d);

        assertEquals(100, properties.recallImportanceMin());
        assertEquals(1.0d, properties.recallNearDupSimilarity(), 1e-9);

        properties.setRecallImportanceMin(-10);
        properties.setRecallNearDupSimilarity(-0.2d);

        assertEquals(0, properties.recallImportanceMin());
        assertEquals(0.0d, properties.recallNearDupSimilarity(), 1e-9);
    }
}

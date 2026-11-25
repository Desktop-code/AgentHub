package com.agenthub.memory.application;

import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 平台级槽解析：只认 platform_locked=1，且必须启用；无平台级即 empty（不回落默认实例、不取第一条）。
 * 全部 mock，零数据库。
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorModelSlotResolverTest {

    @Mock
    private ModelInstanceRepository instances;

    private MemoryVectorModelSlotResolver resolver;

    @BeforeEach
    void setUp() {
        MemoryProperties properties = new MemoryProperties();
        properties.setPlatformModelCacheMs(30_000L);
        resolver = new MemoryVectorModelSlotResolver(instances, properties);
    }

    @Test
    void embeddingResolvesEnabledPlatformInstance() {
        when(instances.findPlatformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(instance(9L, 1, "EMBEDDING")));

        Optional<ModelInstance> found = resolver.embedding();

        assertTrue(found.isPresent());
        assertEquals(9L, found.get().getId());
    }

    @Test
    void disabledPlatformInstanceIsRejectedForOutbound() {
        when(instances.findPlatformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(instance(9L, 0, "EMBEDDING")));

        assertTrue(resolver.embedding().isEmpty());
        // 但保留原始行：memory 域据此给出「平台级已停用」而不是「未设置」
        assertTrue(resolver.platformLocked(ModelCapability.EMBEDDING).isPresent());
    }

    @Test
    void legacyEmbedCodeIsAccepted() {
        // 库内遗留 EMBED 由 ModelCapability 映射为 EMBEDDING；仓储按 storageCodes 兼容
        when(instances.findPlatformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(instance(9L, 1, "EMBED")));

        assertTrue(resolver.embedding().isPresent());
    }

    @Test
    void missingPlatformInstanceReturnsEmpty() {
        assertTrue(resolver.embedding().isEmpty());
        assertTrue(resolver.rerank().isEmpty());
        verify(instances).findPlatformLocked(ModelCapability.EMBEDDING);
        verify(instances).findPlatformLocked(ModelCapability.RERANK);
        verifyNoMoreInteractions(instances);
    }

    @Test
    void resultIsCachedUntilInvalidated() {
        when(instances.findPlatformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(instance(9L, 1, "EMBEDDING")));

        resolver.embedding();
        resolver.embedding();
        // 短缓存命中：两次解析只查一次库
        verify(instances, times(1)).findPlatformLocked(ModelCapability.EMBEDDING);

        resolver.invalidate();
        resolver.embedding();
        // 主动失效后必须重新查库
        verify(instances, times(2)).findPlatformLocked(ModelCapability.EMBEDDING);
    }

    @Test
    void nonRetrievalCapabilityNeverQueriesRepository() {
        assertTrue(resolver.platformLocked(ModelCapability.CHAT).isEmpty());
        verifyNoMoreInteractions(instances);
    }

    private static ModelInstance instance(long id, int status, String capability) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setStatus(status);
        instance.setCapability(capability);
        instance.setPlatformLocked(1);
        return instance;
    }
}

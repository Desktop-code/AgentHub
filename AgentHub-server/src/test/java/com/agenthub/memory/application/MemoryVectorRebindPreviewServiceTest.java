package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 换绑预检的窄单测。所属域：memory。
 * <p>
 * 只验证判定契约，不连库、不连 Milvus：
 * <ul>
 *   <li>同维 / 集合尚未创建 → 不需要重建；</li>
 *   <li>异维 → {@code REBIND_REQUIRES_REBUILD} 且文案点名维不一致；</li>
 *   <li>补同步 / 重建中 → 直接拒绝并给可读原因；</li>
 *   <li>未测连通 / 无维度快照 → 拒绝；</li>
 *   <li>Rerank 换绑 → 无维度判定、无重建步骤。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MemoryVectorRebindPreviewServiceTest {

    private static final String COLLECTION = "agenthub_memory_long";
    private static final long TARGET_ID = 21L;
    private static final long CURRENT_ID = 9L;

    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private MemoryVectorModelSlotResolver slots;
    @Mock
    private MemoryVectorSupport support;
    @Mock
    private MemoryVectorReconciler reconciler;

    private MemoryVectorRebuildState rebuildState;
    private MemoryVectorRebindPreviewService service;

    @BeforeEach
    void setUp() {
        rebuildState = new MemoryVectorRebuildState();
        service = new MemoryVectorRebindPreviewService(instances, slots, support, reconciler, rebuildState);
        // 多数用例都会取配置集合名；实例不存在等用例不会走到这里，用 lenient 避免误报未使用桩
        lenient().when(support.configuredCollection()).thenReturn(COLLECTION);
    }

    @Test
    @DisplayName("同维换绑：只需一级确认，无重建步骤")
    void sameDimensionNeedsNoRebuild() {
        when(instances.findById(TARGET_ID)).thenReturn(Optional.of(embedding(TARGET_ID, 768, 1, 1)));
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(CURRENT_ID, 768, 1, 1)));
        when(support.collectionDimension()).thenReturn(768);

        MemoryVectorRebindPreviewService.Preview preview = service.preview(TARGET_ID);

        assertTrue(preview.allowed());
        assertFalse(preview.rebuildRequired());
        assertEquals("REBIND_SAME_DIM", preview.action());
        assertEquals(CURRENT_ID, preview.currentInstanceId());
        assertEquals(768, preview.collectionDimension());
    }

    @Test
    @DisplayName("集合尚未创建：按同维处理，换绑后自动 ensure 建集")
    void missingCollectionIsTreatedAsSameDimension() {
        when(instances.findById(TARGET_ID)).thenReturn(Optional.of(embedding(TARGET_ID, 1024, 1, 1)));
        when(slots.platformLocked(ModelCapability.EMBEDDING)).thenReturn(Optional.empty());
        when(support.collectionDimension()).thenReturn(null);

        MemoryVectorRebindPreviewService.Preview preview = service.preview(TARGET_ID);

        assertTrue(preview.allowed());
        assertFalse(preview.rebuildRequired());
        assertEquals("SET_EMBEDDING", preview.action());
        assertTrue(preview.warnings().stream().anyMatch(w -> w.contains("尚未创建")));
    }

    @Test
    @DisplayName("异维换绑：必须标记需要受控重建，且文案点名两个维度")
    void dimensionMismatchRequiresRebuild() {
        when(instances.findById(TARGET_ID)).thenReturn(Optional.of(embedding(TARGET_ID, 1024, 1, 1)));
        when(slots.platformLocked(ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(embedding(CURRENT_ID, 768, 1, 1)));
        when(support.collectionDimension()).thenReturn(768);

        MemoryVectorRebindPreviewService.Preview preview = service.preview(TARGET_ID);

        assertTrue(preview.allowed(), "异维仍允许先换绑，重建是后置的独立动作");
        assertTrue(preview.rebuildRequired());
        assertEquals("REBIND_REQUIRES_REBUILD", preview.action());
        assertTrue(preview.warnings().stream().anyMatch(w -> w.contains("1024") && w.contains("768")));
    }

    @Test
    @DisplayName("补同步进行中：拒绝换绑且不出站")
    void syncingBlocksPreview() {
        when(instances.findById(TARGET_ID)).thenReturn(Optional.of(embedding(TARGET_ID, 768, 1, 1)));
        when(reconciler.isRunning()).thenReturn(true);

        MemoryVectorRebindPreviewService.Preview preview = service.preview(TARGET_ID);

        assertFalse(preview.allowed());
        assertEquals("BLOCKED", preview.action());
        assertTrue(preview.reason().contains("补同步进行中"), preview.reason());
        // 被拒时不去解析平台级槽位，也不触碰 Milvus
        verifyNoInteractions(slots);
    }

    @Test
    @DisplayName("重建进行中：拒绝换绑，文案点明重建")
    void rebuildingBlocksPreview() {
        when(instances.findById(TARGET_ID)).thenReturn(Optional.of(embedding(TARGET_ID, 768, 1, 1)));
        when(reconciler.isRunning()).thenReturn(false);
        // 真状态机：进入重建后置 rebuilding=true，覆盖「重建中禁换绑」
        assertTrue(rebuildState.tryBegin());

        MemoryVectorRebindPreviewService.Preview preview = service.preview(TARGET_ID);

        assertFalse(preview.allowed());
        assertTrue(preview.reason().contains("重建进行中"), preview.reason());
        assertTrue(preview.rebuilding());
    }

    @Test
    @DisplayName("未测连通 / 无维度快照：拒绝并给出下一步")
    void untestedOrMissingSnapshotIsBlocked() {
        when(instances.findById(TARGET_ID)).thenReturn(Optional.of(embedding(TARGET_ID, null, 0, 1)));
        when(reconciler.isRunning()).thenReturn(false);

        MemoryVectorRebindPreviewService.Preview preview = service.preview(TARGET_ID);

        assertFalse(preview.allowed());
        assertTrue(preview.reason().contains("连通测试"), preview.reason());
    }

    @Test
    @DisplayName("Rerank 换绑：无维度判定、无重建步骤")
    void rerankRebindHasNoRebuildStep() {
        ModelInstance target = embedding(TARGET_ID, null, 1, 1);
        target.setCapability(ModelCapability.RERANK.name());
        when(instances.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(slots.platformLocked(ModelCapability.RERANK))
                .thenReturn(Optional.of(embedding(CURRENT_ID, null, 1, 1)));

        MemoryVectorRebindPreviewService.Preview preview = service.preview(TARGET_ID);

        assertTrue(preview.allowed());
        assertFalse(preview.rebuildRequired());
        assertEquals("REBIND_RERANK", preview.action());
        assertEquals(null, preview.collectionDimension());
    }

    @Test
    @DisplayName("实例不存在：抛业务异常而不是 500")
    void missingInstanceThrowsBizException() {
        when(instances.findById(TARGET_ID)).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> service.preview(TARGET_ID));
    }

    @Test
    @DisplayName("非 EMBEDDING / RERANK 用途：不可设平台级")
    void otherCapabilityIsNotLockable() {
        ModelInstance target = embedding(TARGET_ID, null, 1, 1);
        target.setCapability(ModelCapability.CHAT.name());
        when(instances.findById(TARGET_ID)).thenReturn(Optional.of(target));

        MemoryVectorRebindPreviewService.Preview preview = service.preview(TARGET_ID);

        assertFalse(preview.allowed());
        assertEquals("NOT_LOCKABLE", preview.action());
    }

    private static ModelInstance embedding(long id, Integer dim, int lastTestOk, int status) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setDisplayName("实例" + id);
        instance.setCapability(ModelCapability.EMBEDDING.name());
        instance.setEmbeddingDim(dim);
        instance.setLastTestOk(lastTestOk);
        instance.setStatus(status);
        return instance;
    }
}

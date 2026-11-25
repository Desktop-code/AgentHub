package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.model.LongExtractMark;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.repository.LongExtractSessionGate;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.FactKeyNormalizer;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.prompt.application.PromptAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话结束批抽止血：已处理不再调；失败终态不再调；空正文不调；总闸/开关关不调。
 */
@ExtendWith(MockitoExtension.class)
class LongExtractServiceSessionEndTest {

    private static final String BODY = "用户：请记住我住上海，以后查天气默认上海。\n助手：好的。\n";

    @Mock
    private MemoryModelClient models;
    @Mock
    private SummaryModelResolver resolver;
    @Mock
    private MemoryItemRepository items;
    @Mock
    private LongExtractSessionGate gate;
    @Mock
    private PromptAssembler prompts;
    @Mock
    private MemoryVectorHook vectors;

    private MemoryProperties properties;
    private LongExtractService extracts;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setLongExtractBatchEnabled(true);
        properties.setLongExtractMinChars(8);
        LongMemoryMerger merger = new LongMemoryMerger(items, new FactKeyNormalizer(Map.of()), vectors);
        ExplicitMemoryStore store = new ExplicitMemoryStore(merger, items, vectors);
        extracts = new LongExtractService(models, resolver, store, gate, properties, prompts);
        // 不是每个用例都会真的出站（总闸/开关/空正文会提前返回），用 lenient 避免严格桩误报
        lenient().when(prompts.assembleLockedByPurpose(anyString(), any(), any()))
                .thenReturn("平台锁定抽取提示词");
    }

    @Test
    void alreadyProcessedDoesNotCallModel() {
        when(gate.isProcessed(3L)).thenReturn(true);
        extracts.onSessionEnd(base(), 4L, 7L, 3L, BODY);
        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(gate, never()).complete(anyLong(), any(), anyString());
    }

    @Test
    void failureMarksFailedAndSecondCallSkipsModel() {
        when(gate.isProcessed(3L)).thenReturn(false).thenReturn(true);
        when(resolver.requireExtract(any(), eq(4L))).thenThrow(new BizException(ErrorCode.BAD_REQUEST, "没有可用的记忆抽取模型"));
        extracts.onSessionEnd(base(), 4L, 7L, 3L, BODY);
        verify(gate).complete(3L, LongExtractMark.FAILED, "BizException:没有可用的记忆抽取模型");
        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());

        extracts.onSessionEnd(base(), 4L, 7L, 3L, BODY);
        verify(resolver, times(1)).requireExtract(any(), eq(4L));
        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void blankTranscriptDoesNotCallModel() {
        when(gate.isProcessed(3L)).thenReturn(false);
        extracts.onSessionEnd(base(), 4L, 7L, 3L, "  \n");
        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(gate).complete(3L, LongExtractMark.SKIPPED, "empty_transcript");
    }

    @Test
    void shortTranscriptDoesNotCallModel() {
        when(gate.isProcessed(3L)).thenReturn(false);
        extracts.onSessionEnd(base(), 4L, 7L, 3L, "用户：嗨");
        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(gate).complete(3L, LongExtractMark.SKIPPED, "short_transcript");
    }

    @Test
    void batchKillSwitchDoesNotCallModel() {
        properties.setLongExtractBatchEnabled(false);
        when(gate.isProcessed(3L)).thenReturn(false);
        extracts.onSessionEnd(base(), 4L, 7L, 3L, BODY);
        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(gate).complete(3L, LongExtractMark.SKIPPED, "batch_kill_switch");
    }

    @Test
    void switchOffDoesNotCallModel() {
        when(gate.isProcessed(3L)).thenReturn(false);
        MemoryPolicy policy = base();
        policy.setLongExtractOnSessionEnd(0);
        extracts.onSessionEnd(policy, 4L, 7L, 3L, BODY);
        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(resolver, never()).requireExtract(policy, 4L);
        verify(gate).complete(3L, LongExtractMark.SKIPPED, "switch_off");
    }

    @Test
    void enabledWithBodyCallsModelAndMarksDone() {
        when(gate.isProcessed(3L)).thenReturn(false);
        MemoryPolicy policy = base();
        when(resolver.requireExtract(policy, 4L)).thenReturn(99L);
        when(models.complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn("[]");
        extracts.onSessionEnd(policy, 4L, 7L, 3L, BODY);
        verify(models).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(items, never()).insert(any(), anyLong());
        verify(gate).complete(3L, LongExtractMark.DONE, "ok");
    }

    @Test
    void wrappedJsonWithFactKeyInsertsLong() {
        when(gate.isProcessed(3L)).thenReturn(false);
        MemoryPolicy policy = base();
        when(resolver.requireExtract(policy, 4L)).thenReturn(99L);
        when(models.complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn("说明：有一条偏好。\n{\"preferences\":[{\"fact_key\":\"residence_city\",\"title\":\"城市\",\"text\":\"用户常住广州\",\"score\":80}]}");
        when(items.findActiveByFactKeys(eq(4L), eq(7L), eq("LONG"), any())).thenReturn(java.util.List.of());
        when(items.findActiveByHash(eq(4L), eq(7L), eq("LONG"), anyString())).thenReturn(Optional.empty());
        when(items.insert(any(MemoryItem.class), eq(7L))).thenReturn(501L);
        extracts.onSessionEnd(policy, 4L, 7L, 3L, "用户：我住广州，以后查天气默认广州\n");
        ArgumentCaptor<MemoryItem> captor = ArgumentCaptor.forClass(MemoryItem.class);
        verify(items).insert(captor.capture(), eq(7L));
        assertEquals("LONG", captor.getValue().getScope());
        assertEquals("residence_city", captor.getValue().getFactKey());
        assertEquals("用户常住广州", captor.getValue().getContent());
        assertEquals(4L, captor.getValue().getAgentId());
        assertEquals(7L, captor.getValue().getUserId());
        verify(items).evictOverflow(4L, 7L, "LONG", 100, 7L);
        verify(gate).complete(3L, LongExtractMark.DONE, "ok");
    }

    @Test
    void missingFactKeyDoesNotInsert() {
        when(gate.isProcessed(3L)).thenReturn(false);
        MemoryPolicy policy = base();
        when(resolver.requireExtract(policy, 4L)).thenReturn(99L);
        when(models.complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn("[{\"title\":\"城市\",\"content\":\"用户常住广州\",\"importance\":80}]");
        extracts.onSessionEnd(policy, 4L, 7L, 3L, "用户：我住在广州，请以后默认用广州。\n");
        verify(items, never()).insert(any(), anyLong());
        verify(items, never()).update(any(), anyLong());
        verify(gate).complete(3L, LongExtractMark.DONE, "ok");
    }

    @Test
    void unparsableProseDoesNotInsert() {
        when(gate.isProcessed(3L)).thenReturn(false);
        MemoryPolicy policy = base();
        when(resolver.requireExtract(policy, 4L)).thenReturn(99L);
        when(models.complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn("我认为用户没有长期偏好，本次不写入记忆。");
        extracts.onSessionEnd(policy, 4L, 7L, 3L, "用户：今天广州天气如何？\n");
        verify(items, never()).insert(any(), anyLong());
        verify(items, never()).update(any(), anyLong());
        verify(gate).complete(3L, LongExtractMark.DONE, "ok");
    }

    /**
     * 批抽正文里助手的纠错独白必须整轮剔除：模型看不到「你是想说苹果吗」，
     * 就不可能在用户未确认时抽出这条偏好（助手单方面纠正不写库）。
     */
    @Test
    void batchStripsAssistantCorrectionBeforeOutbound() {
        when(gate.isProcessed(3L)).thenReturn(false);
        MemoryPolicy policy = base();
        when(resolver.requireExtract(policy, 4L)).thenReturn(99L);
        when(models.complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn("[]");

        extracts.onSessionEnd(policy, 4L, 7L, 3L, "用户：我爱吃评估\n助手：你是想说苹果吗？\n");

        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(models).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), source.capture());
        assertTrue(source.getValue().contains("我爱吃评估"), "用户证据必须保留");
        assertFalse(source.getValue().contains("苹果"), "助手独白不能作为记忆证据出站");
    }

    /** 整段只有助手纠错：没有任何用户证据，不调模型、不写库 */
    @Test
    void batchWithAssistantCorrectionOnlyCallsNoModel() {
        when(gate.isProcessed(3L)).thenReturn(false);
        extracts.onSessionEnd(base(), 4L, 7L, 3L, "助手：你是想说苹果吗？");

        verify(models, never()).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(items, never()).insert(any(), anyLong());
        verify(gate).complete(3L, LongExtractMark.DONE, "ok");
    }

    private static MemoryPolicy base() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        policy.setEnableLongExtract(1);
        policy.setLongExtractOnSessionEnd(1);
        policy.setMaxLongItems(100);
        return policy;
    }
}

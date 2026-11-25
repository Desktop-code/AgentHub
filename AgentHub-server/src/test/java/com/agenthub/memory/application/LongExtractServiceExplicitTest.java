package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.repository.LongExtractSessionGate;
import com.agenthub.memory.domain.service.MemoryCorrectionSignal;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.memory.domain.service.MemoryTexts;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.prompt.application.PromptAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 显式通道的 L2：系统段来自平台记忆抽取模板的锁定段，MEMORY L3 只作为追加区传入，
 * 写库走独立事务的 store。
 */
@ExtendWith(MockitoExtension.class)
class LongExtractServiceExplicitTest {

    private static final long AGENT_ID = 4L;
    private static final long USER_ID = 7L;
    private static final long SESSION_ID = 3L;
    private static final long MODEL_ID = 9L;
    private static final String TEXT = "记住它";
    private static final String EXTRA = "本 Agent 只记工作偏好";
    private static final String LOCKED_PROMPT = "【抽取契约】平台锁定\n【输出格式】平台锁定";

    @Mock
    private MemoryModelClient models;
    @Mock
    private SummaryModelResolver resolver;
    @Mock
    private ExplicitMemoryStore store;
    @Mock
    private LongExtractSessionGate gate;
    @Mock
    private PromptAssembler prompts;

    private LongExtractService service;
    private MemoryPolicy policy;

    @BeforeEach
    void setUp() {
        MemoryProperties properties = new MemoryProperties();
        service = new LongExtractService(models, resolver, store, gate, properties, prompts);
        policy = new MemoryPolicy();
        policy.setEnableLong(1);
        policy.setLongExtractOnExplicit(1);
        policy.setMaxLongItems(100);
    }

    @Test
    void explicitUsesL3ExtraAndRequiresNewStore() {
        when(resolver.requireExtract(policy, AGENT_ID)).thenReturn(MODEL_ID);
        when(resolver.memoryPromptExtra(AGENT_ID)).thenReturn(EXTRA);
        when(prompts.assembleLockedByPurpose("MEMORY", null, EXTRA)).thenReturn(LOCKED_PROMPT);
        when(models.complete(AGENT_ID, SESSION_ID, USER_ID, MODEL_ID, LOCKED_PROMPT, TEXT))
                .thenReturn("[{\"fact_key\":\"residence_city\",\"title\":\"城市\",\"content\":\"广州\",\"importance\":80}]");
        when(store.store(eq(AGENT_ID), eq(USER_ID), any(), eq(100), eq(USER_ID)))
                .thenReturn(new ExplicitMemoryStore.Outcome(1, 0, 0, 0));

        service.onExplicitConfirmed(policy, AGENT_ID, USER_ID, SESSION_ID, TEXT);

        // 系统段只来自平台锁定段组装；L3 只作为追加参数参与组装，不能替换契约/格式
        verify(prompts).assembleLockedByPurpose("MEMORY", null, EXTRA);
        verify(models).complete(AGENT_ID, SESSION_ID, USER_ID, MODEL_ID, LOCKED_PROMPT, TEXT);
        ArgumentCaptor<List<MemoryTexts.PreferenceDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(store).store(eq(AGENT_ID), eq(USER_ID), drafts.capture(), eq(100), eq(USER_ID));
        assertEquals(1, drafts.getValue().size());
        assertEquals("residence_city", drafts.getValue().get(0).getFactKey());
    }

    /**
     * 纠错信号只在出站正文首行加提示，不进平台锁定系统段：
     * 模板口径不动，避免「改模板」与「改代码」两处漂移。
     */
    @Test
    void correctionSignalOnlyPrefixesSourceNotSystemPrompt() {
        when(resolver.requireExtract(policy, AGENT_ID)).thenReturn(MODEL_ID);
        when(resolver.memoryPromptExtra(AGENT_ID)).thenReturn(EXTRA);
        when(prompts.assembleLockedByPurpose("MEMORY", null, EXTRA)).thenReturn(LOCKED_PROMPT);
        when(models.complete(eq(AGENT_ID), eq(SESSION_ID), eq(USER_ID), eq(MODEL_ID), anyString(), anyString()))
                .thenReturn("[]");

        service.onExplicitConfirmed(policy, AGENT_ID, USER_ID, SESSION_ID,
                "不是评估是苹果", MemoryCorrectionSignal.Kind.CORRECTION);

        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(models).complete(eq(AGENT_ID), eq(SESSION_ID), eq(USER_ID), eq(MODEL_ID),
                eq(LOCKED_PROMPT), source.capture());
        assertTrue(source.getValue().startsWith("【用户更正】"));
        assertTrue(source.getValue().endsWith("不是评估是苹果"));
    }
}

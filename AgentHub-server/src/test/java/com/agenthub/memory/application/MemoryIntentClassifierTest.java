package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.MemoryModelClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1 分类：只认 store，normal 与任何异常都按不写入处理；分类调用必须 temperature=0 且输出上限能覆盖推理。
 */
@ExtendWith(MockitoExtension.class)
class MemoryIntentClassifierTest {

    private static final long AGENT_ID = 1L;
    private static final long USER_ID = 2L;
    private static final long SESSION_ID = 3L;
    private static final long MODEL_ID = 9L;
    private static final String TEXT = "我居住地是广州";

    @Mock
    private MemoryModelClient models;
    @Mock
    private SummaryModelResolver resolver;

    private MemoryIntentClassifier classifier;
    private MemoryPolicy policy;

    @BeforeEach
    void setUp() {
        classifier = new MemoryIntentClassifier(models, resolver);
        policy = new MemoryPolicy();
        policy.setEnableLong(1);
        policy.setLongExtractOnExplicit(1);
    }

    @Test
    void storeGoesToL2WithZeroTemperatureAndReasoningSafeMaxTokens() {
        stubResolve();
        when(models.complete(AGENT_ID, SESSION_ID, USER_ID, MODEL_ID,
                MemoryIntentClassifier.INSTRUCTION, null, TEXT, 0.0d, MemoryIntentClassifier.MAX_TOKENS))
                .thenReturn("store");

        assertTrue(classifier.shouldStore(policy, AGENT_ID, USER_ID, SESSION_ID, TEXT));

        // L1 只带代码常量与当轮原文：不拼平台抽取模板、不拼追加要求
        verify(models).complete(AGENT_ID, SESSION_ID, USER_ID, MODEL_ID,
                MemoryIntentClassifier.INSTRUCTION, null, TEXT, 0.0d, MemoryIntentClassifier.MAX_TOKENS);
        verify(resolver, never()).memoryPromptExtra(AGENT_ID);
    }

    @Test
    void l1BudgetMustCoverThinkingNotJustOneWord() {
        // 8 / 32 会被推理模型的思考吃满，只剩 thinking 没有正文；这里锁死下限，防止回退
        assertTrue(MemoryIntentClassifier.MAX_TOKENS >= 256,
                "L1 输出上限必须覆盖思考 + 一个词，不能退回 8 / 32");
    }

    @Test
    void normalDoesNotGoToL2() {
        stubResolve();
        when(models.complete(anyLong(), any(), anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("normal");

        assertFalse(classifier.shouldStore(policy, AGENT_ID, USER_ID, SESSION_ID, TEXT));
    }

    @Test
    void malformedOutputIsTreatedAsNormal() {
        stubResolve();
        when(models.complete(anyLong(), any(), anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("store because user asked");

        assertFalse(classifier.shouldStore(policy, AGENT_ID, USER_ID, SESSION_ID, TEXT));
    }

    @Test
    void emptyModelOutputIsTreatedAsNormal() {
        // 空回不能让漏斗默认成 store：L1 只是省流层，拿不到结论就按 normal 结束
        stubResolve();
        when(models.complete(anyLong(), any(), anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("");

        assertFalse(classifier.shouldStore(policy, AGENT_ID, USER_ID, SESSION_ID, TEXT));
    }

    @Test
    void modelFailureIsTreatedAsNormal() {
        when(resolver.requireExtract(policy, AGENT_ID))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "没有可用的记忆抽取模型"));

        assertFalse(classifier.shouldStore(policy, AGENT_ID, USER_ID, SESSION_ID, TEXT));
    }

    @Test
    void blankTextIsNotClassified() {
        assertFalse(classifier.shouldStore(policy, AGENT_ID, USER_ID, SESSION_ID, " "));
    }

    private void stubResolve() {
        when(resolver.requireExtract(policy, AGENT_ID)).thenReturn(MODEL_ID);
    }
}

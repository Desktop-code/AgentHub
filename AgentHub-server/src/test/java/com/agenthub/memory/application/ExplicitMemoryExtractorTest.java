package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.MemoryCorrectionSignal;
import com.agenthub.memory.domain.service.SessionTurnSource;
import com.agenthub.memory.infra.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 显式记住的异步漏斗。所属域：memory。
 * 核心红线：主线程只投递、不抽取；L0 命中跳过 L1；L1 只有 store 才进 L2；
 * 纯问句/过短/招呼不投递；池满拒绝与任务内失败都不冒泡。
 */
@ExtendWith(MockitoExtension.class)
class ExplicitMemoryExtractorTest {

    private static final String HIT_TEXT = "请记住我住广州";
    /** L0 不命中，但属于可交给 L1 的陈述句 */
    private static final String FACT_TEXT = "我居住地是广州";
    private static final String QUESTION_TEXT = "今天天气怎么样？";
    private static final String RECALL_QUESTION = "记住了吗";
    private static final String SHORT_TEXT = "你好";
    private static final String SHORT_COMMAND = "记住它";

    @Mock
    private ThreadPoolTaskExecutor memoryTaskExecutor;
    @Mock
    private LongExtractService extracts;
    @Mock
    private MemoryPolicyAppService policies;
    @Mock
    private MemoryIntentClassifier classifier;
    @Mock
    private SessionTurnSource events;

    private ExplicitMemoryExtractor extractor;

    @BeforeEach
    void setUp() {
        // L0 用真实规则：本类要验证的正是「命中/误伤」本身，而不是把检测器也 mock 掉
        RememberIntentDetector detector = new RememberIntentDetector(new MemoryProperties());
        extractor = new ExplicitMemoryExtractor(memoryTaskExecutor, extracts, policies, detector, classifier, events);
    }

    /** 开关全开但只说事实：仍会投递 L1，但主线程绝不跑 L2。 */
    @Test
    void factStatementGoesToL1WithoutInlineExtraction() {
        assertTrue(extractor.dispatch(policy(1, 1), 1L, 2L, 3L, FACT_TEXT));

        verify(memoryTaskExecutor).execute(any());
        // 主线程上绝不出现 L2：这就是 N6 红线的断言
        verify(extracts, never()).onExplicitConfirmed(any(), anyLong(), anyLong(), any(), anyString());
    }

    /** 未启用长期记忆：命中词也不能抽，且连线程池都不碰。 */
    @Test
    void longMemoryOffDoesNotSubmit() {
        assertFalse(extractor.dispatch(policy(0, 1), 1L, 2L, 3L, HIT_TEXT));

        verifyNoInteractions(memoryTaskExecutor);
        verifyNoInteractions(extracts);
        // 开关关闭时连 L1 都不该碰，确保「零额外模型调用」
        verifyNoInteractions(classifier);
    }

    /** 关了「显式记住」开关：不投递，且不受批抽开关影响。 */
    @Test
    void explicitSwitchOffDoesNotSubmit() {
        assertFalse(extractor.dispatch(policy(1, 0), 1L, 2L, 3L, HIT_TEXT));

        verifyNoInteractions(memoryTaskExecutor);
        verifyNoInteractions(extracts);
    }

    /** L0 命中：「请记住…」只投递，主线程不跑 L2。 */
    @Test
    void l0HitSubmitsWithoutExtractingInline() {
        assertTrue(extractor.dispatch(policy(1, 1), 1L, 2L, 3L, HIT_TEXT));

        verify(memoryTaskExecutor).execute(any());
        verify(extracts, never()).onExplicitConfirmed(any(), anyLong(), anyLong(), any(), anyString());
    }

    /** L0 命中的任务在池线程上重读策略后直接 L2，不再打 L1。 */
    @Test
    void l0HitQueuedTaskSkipsL1AndExtractsWithFreshPolicy() {
        MemoryPolicy fresh = policy(1, 1);
        when(policies.effective(1L)).thenReturn(fresh);

        extractor.dispatch(policy(1, 1), 1L, 2L, 3L, HIT_TEXT);
        runCapturedTask();

        verify(classifier, never()).shouldStore(any(), anyLong(), anyLong(), any(), anyString());
        verify(extracts).onExplicitConfirmed(eq(fresh), eq(1L), eq(2L), eq(3L), eq(HIT_TEXT), isNull());
    }

    /** L1 判 normal：结束，不得进入 L2。 */
    @Test
    void l1NormalDoesNotReachL2() {
        MemoryPolicy fresh = policy(1, 1);
        when(policies.effective(1L)).thenReturn(fresh);
        when(classifier.shouldStore(fresh, 1L, 2L, 3L, FACT_TEXT)).thenReturn(false);

        extractor.dispatch(policy(1, 1), 1L, 2L, 3L, FACT_TEXT);
        runCapturedTask();

        verify(classifier).shouldStore(fresh, 1L, 2L, 3L, FACT_TEXT);
        verify(extracts, never()).onExplicitConfirmed(any(), anyLong(), anyLong(), any(), anyString());
    }

    /** L1 判 store：继续 L2。 */
    @Test
    void l1StoreReachesL2() {
        MemoryPolicy fresh = policy(1, 1);
        when(policies.effective(1L)).thenReturn(fresh);
        when(classifier.shouldStore(fresh, 1L, 2L, 3L, FACT_TEXT)).thenReturn(true);
        when(events.replay(3L)).thenReturn(List.of());

        extractor.dispatch(policy(1, 1), 1L, 2L, 3L, FACT_TEXT);
        runCapturedTask();

        verify(extracts).onExplicitConfirmed(eq(fresh), eq(1L), eq(2L), eq(3L), eq(FACT_TEXT), isNull());
    }

    /**
     * 用户更正：L0 未命中，但属于纠错意图，必须跳过 L1 直接进 L2，
     * 并把 CORRECTION 信号传给抽取（否则「打错了」会被 L1 判 normal 直接丢掉）。
     */
    @Test
    void userCorrectionBypassesL1AndCarriesSignal() {
        String correction = "不是评估是苹果";
        MemoryPolicy fresh = policy(1, 1);
        when(policies.effective(1L)).thenReturn(fresh);
        when(events.replay(3L)).thenReturn(List.of());

        assertTrue(extractor.dispatch(policy(1, 1), 1L, 2L, 3L, correction));
        runCapturedTask();

        verify(classifier, never()).shouldStore(any(), anyLong(), anyLong(), any(), anyString());
        verify(extracts).onExplicitConfirmed(eq(fresh), eq(1L), eq(2L), eq(3L),
                eq(correction), eq(MemoryCorrectionSignal.Kind.CORRECTION));
    }

    /** 用户确认助手给出的正确值：同样跳过 L1，带 CONFIRMATION 信号 */
    @Test
    void userConfirmationBypassesL1AndCarriesSignal() {
        String confirmation = "对，是苹果";
        MemoryPolicy fresh = policy(1, 1);
        when(policies.effective(1L)).thenReturn(fresh);
        when(events.replay(3L)).thenReturn(List.of());

        assertTrue(extractor.dispatch(policy(1, 1), 1L, 2L, 3L, confirmation));
        runCapturedTask();

        verify(classifier, never()).shouldStore(any(), anyLong(), anyLong(), any(), anyString());
        verify(extracts).onExplicitConfirmed(eq(fresh), eq(1L), eq(2L), eq(3L),
                eq(confirmation), eq(MemoryCorrectionSignal.Kind.CONFIRMATION));
    }

    /** 纯问句即使带「不是」也不能当更正去触发抽取 */
    @Test
    void questionWithNegationIsNotCorrection() {
        assertFalse(extractor.dispatch(policy(1, 1), 1L, 2L, 3L, "不是评估是苹果吗？"));

        verifyNoInteractions(memoryTaskExecutor);
    }

    /** 纯问句：L0 未命中且明显不像存储意图，不投递 L1。 */
    @Test
    void pureQuestionDoesNotSubmit() {
        assertFalse(extractor.dispatch(policy(1, 1), 1L, 2L, 3L, QUESTION_TEXT));

        verifyNoInteractions(memoryTaskExecutor);
        verifyNoInteractions(classifier);
    }

    /** 「记住了吗」是询问：L0 排除，且问句闸门也不放行。 */
    @Test
    void recallQuestionDoesNotSubmit() {
        assertFalse(extractor.dispatch(policy(1, 1), 1L, 2L, 3L, RECALL_QUESTION));

        verifyNoInteractions(memoryTaskExecutor);
    }

    /** 过短且无存储意味：不投递 L1。 */
    @Test
    void shortNoiseDoesNotSubmit() {
        assertFalse(extractor.dispatch(policy(1, 1), 1L, 2L, 3L, SHORT_TEXT));

        verifyNoInteractions(memoryTaskExecutor);
    }

    /** 「记住它」：L0 必须命中（不按长度丢弃），L2 用上一轮上下文补全后再抽。 */
    @Test
    void shortCommandExpandsPreviousTurns() {
        MemoryPolicy fresh = policy(1, 1);
        when(policies.effective(1L)).thenReturn(fresh);
        when(events.replay(3L)).thenReturn(List.of(
                ContextTurn.of("user", "我住在广州天河"),
                ContextTurn.of("assistant", "好的，已了解"),
                ContextTurn.of("user", SHORT_COMMAND)));

        extractor.dispatch(policy(1, 1), 1L, 2L, 3L, SHORT_COMMAND);
        runCapturedTask();

        ArgumentCaptor<String> source = ArgumentCaptor.forClass(String.class);
        verify(extracts).onExplicitConfirmed(eq(fresh), eq(1L), eq(2L), eq(3L), source.capture(), isNull());
        String value = source.getValue();
        assertTrue(value.contains("我住在广州天河"));
        assertTrue(value.contains("【最近会话】"));
        assertTrue(value.contains("【本轮用户】"));
        // 当前这句不能重复出现在历史段里
        String history = value.substring(0, value.indexOf("【本轮用户】"));
        assertFalse(history.contains(SHORT_COMMAND));
    }

    /** 池满：拒绝异常不得冒泡到请求线程，主回答照常返回。 */
    @Test
    void rejectedSubmissionIsSwallowed() {
        doThrow(new TaskRejectedException("pool full")).when(memoryTaskExecutor).execute(any());

        assertDoesNotThrow(() -> assertTrue(extractor.dispatch(policy(1, 1), 1L, 2L, 3L, HIT_TEXT)));
    }

    /** 任务内抽取抛异常：只记日志，不冒泡（池线程不被单个任务打挂）。 */
    @Test
    void taskFailureDoesNotPropagate() {
        when(policies.effective(1L)).thenReturn(policy(1, 1));
        doThrow(new IllegalStateException("model down"))
                .when(extracts).onExplicitConfirmed(any(), anyLong(), anyLong(), any(), anyString(), any());

        extractor.dispatch(policy(1, 1), 1L, 2L, 3L, HIT_TEXT);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(memoryTaskExecutor).execute(task.capture());
        assertDoesNotThrow(task.getValue()::run);
    }

    /** 没有 Agent 维度（虚拟根/缺 id）：不投递，避免写进 0 号归属。 */
    @Test
    void missingAgentDoesNotSubmit() {
        assertFalse(extractor.dispatch(policy(1, 1), null, 2L, 3L, HIT_TEXT));

        verifyNoInteractions(memoryTaskExecutor);
    }

    /**
     * 取出投递的 Runnable 并执行，模拟池线程。
     */
    private void runCapturedTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(memoryTaskExecutor).execute(task.capture());
        task.getValue().run();
    }

    private static MemoryPolicy policy(int enableLong, int onExplicit) {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(enableLong);
        policy.setLongExtractOnExplicit(onExplicit);
        // 批抽开关保持关闭：显式通道本就不受它约束
        policy.setEnableLongExtract(0);
        policy.setLongExtractOnSessionEnd(0);
        return policy;
    }
}

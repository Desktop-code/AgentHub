package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.EffectiveLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 次数与连续失败撞限。
 */
class RunBudgetTest {

    @Test
    void maxLlmCallsAborts() {
        RunBudget budget = new RunBudget(limits(2, 10, 3));
        budget.beforeLlm();
        budget.beforeLlm();
        TrialFailure ex = assertThrows(TrialFailure.class, budget::beforeLlm);
        assertEquals(LimitHitReasons.MAX_LLM_CALLS, ex.getFinishReason());
    }

    @Test
    void consecutiveFailAborts() {
        RunBudget budget = new RunBudget(limits(10, 10, 2));
        budget.beforeTool();
        budget.afterTool(false);
        budget.beforeTool();
        TrialFailure ex = assertThrows(TrialFailure.class, () -> budget.afterTool(false));
        assertEquals(LimitHitReasons.CONSECUTIVE_FAIL, ex.getFinishReason());
    }

    @Test
    void successResetsConsecutiveFails() {
        RunBudget budget = new RunBudget(limits(10, 10, 2));
        budget.beforeTool();
        budget.afterTool(false);
        budget.beforeTool();
        budget.afterTool(true);
        budget.beforeTool();
        budget.afterTool(false);
    }

    /**
     * 熔断按专家分开：换个专家后计数清零，前一个专家的连败不连坐后一个。
     * <p>
     * 真实场景：制度知识专家连续失败把自己打断，报告撰写专家仍要能调工具写稿。
     */
    @Test
    void consecutiveFailsResetWhenExpertSwitches() {
        RunBudget budget = new RunBudget(limits(10, 10, 2));
        budget.beforeTool();
        budget.afterTool("agent-1", false);
        // 切到另一个专家：计数清零，所以再来一次失败也不该撞限
        budget.beforeTool();
        budget.afterTool("agent-2", false);
        // 同一专家继续失败才会打断它自己
        budget.beforeTool();
        TrialFailure ex = assertThrows(TrialFailure.class, () -> budget.afterTool("agent-2", false));
        assertEquals(LimitHitReasons.CONSECUTIVE_FAIL, ex.getFinishReason());
    }

    /**
     * 单专家内部仍然保留熔断：同一归属连续失败照样打断，机制没被取消。
     */
    @Test
    void sameExpertStillTripsAbort() {
        RunBudget budget = new RunBudget(limits(10, 10, 2));
        budget.beforeTool();
        budget.afterTool("agent-7", false);
        budget.beforeTool();
        TrialFailure ex = assertThrows(TrialFailure.class, () -> budget.afterTool("agent-7", false));
        assertEquals(LimitHitReasons.CONSECUTIVE_FAIL, ex.getFinishReason());
    }

    /**
     * 首错必须留下，且不被后续熔断文案覆盖。
     *
     * <p>这是用户手点的问题：并行多路 searchKnowledgeBase 全失败后，胶囊、完整链路、
     * 后端日志里只剩「撞限：consecutive_fail_abort」，第一次真实异常完全查不到。
     */
    @Test
    void firstFailureIsKeptAndNotOverwrittenByAbort() {
        RunBudget budget = new RunBudget(limits(10, 10, 3));
        IllegalStateException first = new IllegalStateException("集合 agenthub_memory_long 不存在");

        budget.beforeTool();
        budget.afterTool("policy-expert", false, "searchKnowledgeBase", first);

        // 第二、三次失败（其中一次会触发熔断）不得覆盖首错
        budget.beforeTool();
        budget.afterTool("policy-expert", false, "searchKnowledgeBase", new RuntimeException("撞限前的第 2 次"));
        budget.beforeTool();
        assertThrows(TrialFailure.class,
                () -> budget.afterTool("policy-expert", false, "searchKnowledgeBase", new RuntimeException("第 3 次")));

        RunBudget.FirstFailure kept = budget.firstFailure();
        assertEquals("searchKnowledgeBase", kept.tool());
        assertEquals(true, kept.detail().contains("集合 agenthub_memory_long 不存在"), kept.detail());
        assertEquals(true, kept.detail().contains("IllegalStateException"), kept.detail());
        // 首错不能被熔断文案替换
        assertEquals(false, kept.detail().contains("consecutive_fail_abort"), kept.detail());
    }

    /**
     * 没捕获到异常时留明确说明，而不是留空或猜测原因。
     */
    @Test
    void firstFailureWithoutExceptionSaysNotRecorded() {
        RunBudget budget = new RunBudget(limits(10, 10, 3));
        budget.beforeTool();
        budget.afterTool("agent-1", false, "workspaceListFiles", null);

        RunBudget.FirstFailure kept = budget.firstFailure();
        assertEquals("workspaceListFiles", kept.tool());
        assertEquals(true, kept.detail().contains("未记录到首错"), kept.detail());
        // 绝不猜测具体组件
        assertEquals(false, kept.detail().toLowerCase().contains("milvus"), kept.detail());
        assertEquals(false, kept.detail().toLowerCase().contains("embedding"), kept.detail());
    }

    /**
     * 引擎只给失败状态 + 错误正文时，首错必须是正文本身。
     *
     * <p>这是用户手点的另一半：{@code ToolResultBlock.state == ERROR} 没有异常对象，
     * 原先传 null 让首错变成「未记录到首错」。
     */
    @Test
    void firstFailureFallsBackToErrorText() {
        RunBudget budget = new RunBudget(limits(10, 10, 3));
        budget.beforeTool();
        budget.afterTool("policy-expert", false, "searchKnowledgeBase", null,
                "集合 agenthub_knowledge 不存在（404）");

        RunBudget.FirstFailure kept = budget.firstFailure();
        assertEquals("searchKnowledgeBase", kept.tool());
        assertEquals(true, kept.detail().contains("agenthub_knowledge"), kept.detail());
        assertEquals("FATAL", kept.kind());
        assertEquals(false, kept.detail().contains("未记录到首错"), kept.detail());
    }

    /**
     * 超时类错误正文按可重试记录，不误判成业务错误。
     */
    @Test
    void errorTextIsClassifiedForRetry() {
        RunBudget budget = new RunBudget(limits(10, 10, 3));
        budget.beforeTool();
        budget.afterTool("policy-expert", false, "searchKnowledgeBase", null, "connect timed out");

        assertEquals("RETRYABLE", budget.firstFailure().kind());
        assertEquals(ToolFailureKind.Kind.UNKNOWN, ToolFailureKind.ofText("认不出的情况"));
    }

    /**
     * 切换专家后首错重新计：新专家的第一次失败才是它的首错。
     */
    @Test
    void firstFailureResetsWhenExpertSwitches() {
        RunBudget budget = new RunBudget(limits(10, 10, 3));
        budget.beforeTool();
        budget.afterTool("agent-1", false, "searchKnowledgeBase", new IllegalStateException("甲的错"));

        budget.beforeTool();
        budget.afterTool("agent-2", false, "workspaceListFiles", new IllegalArgumentException("乙的错"));

        RunBudget.FirstFailure kept = budget.firstFailure();
        assertEquals("workspaceListFiles", kept.tool());
        assertEquals(true, kept.detail().contains("乙的错"), kept.detail());
    }

    private EffectiveLimits limits(int maxLlm, int maxTool, int consecutive) {
        return new EffectiveLimits(60_000L, 8, 30_000L, 0, maxTool, 30_000L, maxLlm, consecutive, 8000);
    }
}

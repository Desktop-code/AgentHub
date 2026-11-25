package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanStep;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子计划批准登记表 + 单批准槽的窄测试。所属域：runtime。
 *
 * <p>只验证进程内状态机，不碰库/SSE/模型：
 * 1) 同一根会话的批准槽互斥，且释放后能立刻被下一个子抢到；
 * 2) 批准 / 拒绝 / 清理都只兑现一次，重复调用按「已失效」回 false；
 * 3) 按 route_key 定位待批项，键不匹配时不能误批别人的计划。
 */
class ChildPlanApprovalRegistryTest {

    @Test
    void slotIsExclusiveAndReusableAfterRelease() {
        ChildPlanApprovalRegistry registry = new ChildPlanApprovalRegistry();

        assertTrue(registry.claimSlot(1L, 0L), "首个需确认的子应拿到槽");
        assertFalse(registry.claimSlot(1L, 0L), "槽被占时第二个子不得同时进入待批");

        registry.releaseSlot(1L);
        assertTrue(registry.claimSlot(1L, 0L), "释放后排队子应能拿到槽");
        // 不同根会话互不影响：并行试跑各自都能有一个待批子
        assertTrue(registry.claimSlot(2L, 0L));
    }

    @Test
    void waitingChildGetsSlotWhenHolderReleases() throws Exception {
        ChildPlanApprovalRegistry registry = new ChildPlanApprovalRegistry();
        assertTrue(registry.claimSlot(1L, 0L));

        CountDownLatch claimed = new CountDownLatch(1);
        AtomicBoolean second = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            second.set(registry.claimSlot(1L, 3000L));
            claimed.countDown();
        });
        waiter.start();
        // 先确认它确实在等（不是立刻失败），再释放槽
        Thread.sleep(150L);
        assertFalse(claimed.await(100L, TimeUnit.MILLISECONDS), "槽未释放前排队子不应拿到");
        registry.releaseSlot(1L);
        assertTrue(claimed.await(2000L, TimeUnit.MILLISECONDS), "释放后应唤醒排队子");
        assertTrue(second.get());
    }

    @Test
    void approveCompletesDecisionOnce() {
        ChildPlanApprovalRegistry registry = new ChildPlanApprovalRegistry();
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        registry.publish(pending(1L, "a", decision));

        assertEquals("a", registry.peek(1L).orElseThrow().routeKey());
        assertTrue(registry.approve(1L, "a"));
        assertTrue(decision.getNow(Boolean.FALSE), "批准应唤醒子线程");
        assertTrue(registry.peek(1L).isEmpty(), "批准后登记项必须移除，避免重复批");
        assertFalse(registry.approve(1L, "a"), "重复批准按已失效处理");
    }

    @Test
    void rejectCompletesFalseAndKeepsSlotForOthers() {
        ChildPlanApprovalRegistry registry = new ChildPlanApprovalRegistry();
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        registry.publish(pending(1L, "a", decision));

        assertTrue(registry.reject(1L, "a"));
        assertFalse(decision.getNow(Boolean.TRUE), "拒绝应让子收成失败占位");
        assertTrue(registry.peek(1L).isEmpty());
    }

    @Test
    void routeKeyMismatchDoesNotApproveOtherChild() {
        ChildPlanApprovalRegistry registry = new ChildPlanApprovalRegistry();
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        registry.publish(pending(1L, "a", decision));

        assertFalse(registry.approve(1L, "b"), "批错 key 不能顺手批掉当前唯一待批");
        assertFalse(decision.isDone());
        // 空 key 表示「当前唯一待批」：前端老版本不带 route_key 时仍能批准
        assertTrue(registry.approve(1L, null));
        assertTrue(decision.getNow(Boolean.FALSE));
    }

    @Test
    void clearRejectsWaiterAndRejectAllReleasesSlot() {
        ChildPlanApprovalRegistry registry = new ChildPlanApprovalRegistry();
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        registry.publish(pending(1L, "a", decision));
        registry.claimSlot(1L, 0L);

        registry.clear(1L, "a");
        assertFalse(decision.getNow(Boolean.TRUE), "收尾清理时等待方必须以拒绝收口，不能悬挂");

        CompletableFuture<Boolean> second = new CompletableFuture<>();
        registry.claimSlot(1L, 0L);
        registry.publish(pending(1L, "b", second));
        registry.rejectAll(1L);
        assertFalse(second.getNow(Boolean.TRUE));
        assertTrue(registry.peek(1L).isEmpty());
        assertTrue(registry.claimSlot(1L, 0L), "根取消后槽必须释放，供下一轮使用");
    }

    /**
     * 造一条待批子计划。步骤内容不重要，这里只关心 key / 决定信号。
     *
     * @param sessionId 根会话
     * @param routeKey  路由键
     * @param decision  子线程等待的决定
     * @return 可直接 publish 的登记项
     */
    private static ChildPlanApprovalRegistry.PendingChildPlan pending(Long sessionId, String routeKey,
                                                                     CompletableFuture<Boolean> decision) {
        PendingPlan plan = new PendingPlan("plan-1", sessionId, 2L, 7L, "子问题", "父文本",
                List.of(new PlanStep(1, "查资料", "")), LocalDateTime.now());
        ChildPlanPendingView view = new ChildPlanPendingView(routeKey, 2L, "规划专家", "plan-1",
                PlanState.PENDING.name(), plan.steps(), ChildPlanPendingView.RUN_STATE_PAUSED);
        return new ChildPlanApprovalRegistry.PendingChildPlan(sessionId, view.routeKey(), 2L,
                view.childAgentName(), plan, decision, LocalDateTime.now());
    }
}

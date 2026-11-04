package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.PendingPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子专家计划批准登记表（含单批准槽）。所属域：runtime。
 *
 * <p>为什么需要：P&E 叶子在编排根里跑时，规划与执行发生在根试跑的同一个请求线程/子线程上，
 * 而「批准」来自另一个 HTTP 请求。必须把规划时那份 {@link PendingPlan}（含已注入记忆的用户侧文本）
 * 暂存在进程内，等批准请求唤醒子线程，否则重拼记忆会把滚动摘要推进两遍。
 *
 * <p>硬约束：同一个根会话同一时刻只允许 **1 个**待批准子。
 * 用 {@link #claimSlot(Long, long)} 抢占（putIfAbsent 原子），占不到的子先等待、不开始规划，
 * 因此不会出现两张待批准卡片；REACT 子与 require_plan_confirm=false 的 P&E 子不占槽，照常并行。
 *
 * <p>边界：与 {@link PlanPendingRegistry} 一样是进程内状态。多实例部署需粘性路由（一期单实例）；
 * 重启后登记项消失，批准接口回「没有待批准的子计划」由用户重问，不做静默降级执行。
 */
@Component
public class ChildPlanApprovalRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChildPlanApprovalRegistry.class);

    /** 轮询间隔：等待槽位/决定时最长睡这么久，保证根取消或超时能及时收口 */
    private static final long POLL_MS = 300L;

    /**
     * 一条待批准的子计划。
     *
     * @param rootSessionId 根试跑会话（批准接口按它 + routeKey 定位）
     * @param routeKey      子在父编排里的路由键，空串也允许（按子 Agent 主键兜底定位）
     * @param childAgentId  子 Agent 主键
     * @param childAgentName 子 Agent 展示名
     * @param plan          规划结果与续跑上下文
     * @param decision      批准(true) / 拒绝(false)；子线程阻塞等待它
     * @param createTime    登记时刻
     */
    public record PendingChildPlan(Long rootSessionId, String routeKey, Long childAgentId, String childAgentName,
                                   PendingPlan plan, CompletableFuture<Boolean> decision, LocalDateTime createTime) {
    }

    /** 批准槽：每个根会话一个标记，占住即「已有子计划待批准」。 */
    private final Map<Long, Boolean> slots = new ConcurrentHashMap<>();

    /** 当前待批子：每根会话最多一条。 */
    private final Map<Long, PendingChildPlan> pending = new ConcurrentHashMap<>();

    /** 等待通知用的监视器；有界轮询兜底，即便通知丢失也不会永久挂住。 */
    private final Map<Long, Object> monitors = new ConcurrentHashMap<>();

    /**
     * 抢占批准槽。占不到时按间隔轮询直到超时，期间该子不开始规划。
     *
     * @param sessionId 根会话
     * @param waitMs    最长等待毫秒；≤0 表示只试一次
     * @return true 表示已占住，调用方必须在 finally 里 {@link #releaseSlot(Long)}
     */
    public boolean claimSlot(Long sessionId, long waitMs) {
        if (sessionId == null) {
            return false;
        }
        long deadlineNanos = System.nanoTime() + Math.max(waitMs, 0L) * 1_000_000L;
        Object monitor = monitors.computeIfAbsent(sessionId, key -> new Object());
        while (true) {
            // putIfAbsent 是跨线程原子的：即便不同线程拿到不同监视器，也不会同时占住两个槽
            if (slots.putIfAbsent(sessionId, Boolean.TRUE) == null) {
                return true;
            }
            long remainMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainMs <= 0) {
                return false;
            }
            synchronized (monitor) {
                try {
                    monitor.wait(Math.min(POLL_MS, remainMs));
                } catch (InterruptedException ex) {
                    // 请求被取消/停机：恢复中断标记，由上层按「未获得槽」收成失败占位
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    /**
     * 释放批准槽并唤醒等待者。重复调用安全。
     *
     * @param sessionId 根会话
     */
    public void releaseSlot(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        slots.remove(sessionId);
        Object monitor = monitors.get(sessionId);
        if (monitor != null) {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
        // 没有待批也没有槽时清掉监视器；有界轮询保证即便在此刻有人正等待也不会丢失唤醒
        if (!slots.containsKey(sessionId) && !pending.containsKey(sessionId)) {
            monitors.remove(sessionId);
        }
    }

    /**
     * 登记待批准子计划并发布 SSE 前的状态。
     *
     * @param plan 待批子计划
     */
    public void publish(PendingChildPlan plan) {
        if (plan == null || plan.rootSessionId() == null) {
            return;
        }
        PendingChildPlan previous = pending.put(plan.rootSessionId(), plan);
        if (previous != null && !previous.decision().isDone()) {
            // 理论上不会发生（槽保证串行），出现即说明上游漏了 clear：让旧等待者以拒绝收口，避免挂死
            previous.decision().complete(Boolean.FALSE);
            log.warn("子计划待批被覆盖 rootSessionId={} routeKey={}", plan.rootSessionId(), plan.routeKey());
        }
        log.info("子计划待批准已登记 rootSessionId={} routeKey={} childAgentId={} steps={}",
                plan.rootSessionId(), plan.routeKey(), plan.childAgentId(), plan.plan().steps().size());
    }

    /**
     * @param sessionId 根会话
     * @return 当前待批子计划；没有时为空
     */
    public Optional<PendingChildPlan> peek(Long sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(pending.get(sessionId));
    }

    /**
     * 按 route_key 查找待批子计划；routeKey 为空时按「当前唯一待批」返回，
     * 兼容候选没有 route_key 的编排（此时本来也只有一条待批）。
     *
     * @param sessionId 根会话
     * @param routeKey  路由键，可空
     * @return 命中的待批子计划
     */
    public Optional<PendingChildPlan> find(Long sessionId, String routeKey) {
        Optional<PendingChildPlan> current = peek(sessionId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (routeKey == null || routeKey.isBlank()) {
            return current;
        }
        PendingChildPlan item = current.get();
        return item.routeKey() != null && item.routeKey().equals(routeKey.trim()) ? current : Optional.empty();
    }

    /**
     * 批准并移除待批项。只有第一次调用会成功，重复批准按「已失效」处理。
     *
     * @param sessionId 根会话
     * @param routeKey  路由键，可空
     * @return true 表示确实唤醒了一个等待中的子
     */
    public boolean approve(Long sessionId, String routeKey) {
        return decide(sessionId, routeKey, true);
    }

    /**
     * 拒绝并移除待批项；子线程据 false 收成失败占位，其它子继续。
     *
     * @param sessionId 根会话
     * @param routeKey  路由键，可空
     * @return true 表示确实拒绝了一个等待中的子
     */
    public boolean reject(Long sessionId, String routeKey) {
        return decide(sessionId, routeKey, false);
    }

    /**
     * 子执行收尾清理：按 routeKey 精确移除，若仍在等待则以拒绝收口，避免线程悬挂。
     *
     * @param sessionId 根会话
     * @param routeKey  路由键
     */
    public void clear(Long sessionId, String routeKey) {
        if (sessionId == null) {
            return;
        }
        PendingChildPlan item = pending.get(sessionId);
        if (item == null) {
            return;
        }
        if (routeKey != null && !routeKey.isBlank() && item.routeKey() != null
                && !item.routeKey().equals(routeKey)) {
            return;
        }
        if (pending.remove(sessionId, item)) {
            item.decision().complete(Boolean.FALSE);
        }
    }

    /**
     * 根会话被取消 / 清理时调用：拒绝全部待批子并释放槽。
     *
     * @param sessionId 根会话
     */
    public void rejectAll(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        PendingChildPlan item = pending.remove(sessionId);
        if (item != null) {
            item.decision().complete(Boolean.FALSE);
        }
        releaseSlot(sessionId);
    }

    private boolean decide(Long sessionId, String routeKey, boolean approved) {
        Optional<PendingChildPlan> found = find(sessionId, routeKey);
        if (found.isEmpty()) {
            return false;
        }
        PendingChildPlan item = found.get();
        if (!pending.remove(sessionId, item)) {
            return false;
        }
        // complete 返回 false 说明等待方已放弃（超时/取消）：按「没批到」处理，让接口如实回错
        item.decision().complete(approved);
        log.info("子计划{} rootSessionId={} routeKey={} childAgentId={}",
                approved ? "已批准" : "已拒绝", sessionId, item.routeKey(), item.childAgentId());
        return true;
    }
}

package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.PendingPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待批准计划的内存登记表。所属域：runtime。
 *
 * <p>为什么放进程内：批准后要继续执行，必须复用规划时那一份「已拼好摘要/近轮/短期记忆」的用户侧文本，
 * 重新拼会把窗口滚动摘要推进两遍，导致同一轮被计两次摘要。该数据本来也只在本次请求上下文里有。
 *
 * <p>边界：多实例部署时批准请求必须落到同一实例（一期控制台单实例，未做粘性路由）；
 * 服务重启或超时后登记项消失，批准接口据 {@link #take(Long, long)} 的空结果回复「计划已失效」，
 * 由用户重新提问，不做静默降级执行。
 */
@Component
public class PlanPendingRegistry {

    private static final Logger log = LoggerFactory.getLogger(PlanPendingRegistry.class);

    /** 待批准计划的有效期。超时视为失效，避免长期挂着一条谁也批不动的计划 */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final Map<Long, PendingPlan> bySession = new ConcurrentHashMap<>();

    /**
     * 登记一份待批准计划。同一会话已有登记项时直接覆盖（用户重新提问即放弃旧的）。
     *
     * @param plan 计划快照
     */
    public void put(PendingPlan plan) {
        if (plan == null || plan.sessionId() == null) {
            return;
        }
        purgeExpired();
        bySession.put(plan.sessionId(), plan);
        log.info("计划待批准已登记 sessionId={} steps={}", plan.sessionId(), plan.steps().size());
    }

    /**
     * 取出并移除。用于批准 / 取消，保证同一份计划只能被消费一次。
     *
     * @param sessionId 会话主键
     * @param userId    当前登录用户，用于校验归属
     * @return 有效登记项；不存在、已过期或不属于该用户时为空
     */
    public Optional<PendingPlan> take(Long sessionId, long userId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        PendingPlan plan = bySession.get(sessionId);
        if (plan == null) {
            return Optional.empty();
        }
        if (plan.userId() != userId || expired(plan)) {
            bySession.remove(sessionId, plan);
            return Optional.empty();
        }
        // remove(键, 值) 保证并发下只有一条请求能真正拿到它
        return bySession.remove(sessionId, plan) ? Optional.of(plan) : Optional.empty();
    }

    /**
     * 只读查看，不改状态。供状态接口判断「是否仍在等待批准」。
     *
     * @param sessionId 会话主键
     * @return 有效登记项；没有时为空
     */
    public Optional<PendingPlan> peek(Long sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        PendingPlan plan = bySession.get(sessionId);
        if (plan == null) {
            return Optional.empty();
        }
        if (expired(plan)) {
            bySession.remove(sessionId, plan);
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    /**
     * 丢弃该会话的登记项。清空 / 删除会话时调用。
     *
     * @param sessionId 会话主键
     */
    public void drop(Long sessionId) {
        if (sessionId != null) {
            bySession.remove(sessionId);
        }
    }

    private boolean expired(PendingPlan plan) {
        return plan.createTime() != null && plan.createTime().plus(TTL).isBefore(LocalDateTime.now());
    }

    private void purgeExpired() {
        bySession.entrySet().removeIf(entry -> expired(entry.getValue()));
    }
}

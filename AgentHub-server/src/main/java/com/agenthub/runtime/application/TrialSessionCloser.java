package com.agenthub.runtime.application;

import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.SessionIdle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 试跑会话的取消结案，以及空闲时的收口终态 + 长期偏好批抽。所属域：runtime。
 * 已取消懒结案：新建会话 / 拉列表不再写 SUCCESS。
 * 定时空闲：先把会话标 SUCCESS + ended_at，再异步口径串行批抽；抽取失败不回滚终态、不打回 RUNNING。
 * 单轮最多处理 N 个会话；删除 RUNNING 前也走同一套 onClosed 幂等批抽。
 * 方法不加入外层事务，避免抽模型时占着业务事务。
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class TrialSessionCloser {

    private static final Logger log = LoggerFactory.getLogger(TrialSessionCloser.class);

    private final RunSessionRepository sessions;
    private final MemoryPolicyAppService policies;
    private final MemoryProperties memoryProperties;
    private final TrialFlight flight;
    private final TrialObsHooks obs;
    private final TrialMemoryCoordinator memory;

    /**
     * @param sessions          会话
     * @param policies          读取 ctx_idle_ttl_sec
     * @param memoryProperties  单轮上限 / 批抽间隔
     * @param flight            正在执行的一轮不抽、不取消
     * @param obs               取消时把未结束的调用改成 CANCELLED
     * @param memory            空闲批抽 / 取消后抽 / 删除前抽；清空不走这里
     */
    public TrialSessionCloser(RunSessionRepository sessions, MemoryPolicyAppService policies,
                              MemoryProperties memoryProperties, TrialFlight flight,
                              TrialObsHooks obs, TrialMemoryCoordinator memory) {
        this.sessions = sessions;
        this.policies = policies;
        this.memoryProperties = memoryProperties;
        this.flight = flight;
        this.obs = obs;
        this.memory = memory;
    }

    /**
     * 定时扫进行中的会话：到空闲先收口 SUCCESS，再批抽 LONG。
     * 单轮最多 N 个；单个失败不影响其余；抽取侧幂等/失败短路/空正文跳过。
     */
    public void sweep() {
        List<RunSession> running = sessions.listRunning(null, null);
        LocalDateTime now = LocalDateTime.now();
        int limit = Math.max(1, memoryProperties.getSessionCloseBatchLimit());
        int handled = 0;
        for (RunSession session : running) {
            if (handled >= limit) {
                log.info("空闲收口达单轮上限 limit={} remainingSkippedApprox={}",
                        limit, Math.max(0, running.size() - handled));
                break;
            }
            try {
                if (!SessionIdle.due(session, idleSec(session.getAgentId()), now, flight.running(session.getId()))) {
                    continue;
                }
                // 先终态：抽取失败不得把会话打回 RUNNING
                closeIdle(session, now);
                memory.onClosed(session);
                handled++;
                sleepBetween();
            } catch (RuntimeException ex) {
                // 不把异常抛到 scheduling 线程空转刷；已终态的会话不会再进 listRunning。
                // 必须带 Throwable：收口失败会让会话一直挂在 RUNNING，栈是唯一线索
                log.warn("空闲收口或批抽失败 sessionId={} type={} cause={}",
                        session == null ? null : session.getId(),
                        ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            }
        }
    }

    /**
     * 取消入口。调用方已确认会话仍是 RUNNING 且本轮没在跑。
     * 写成 CANCELLED + ended_at，再抽长期偏好。
     *
     * @param session    会话
     * @param operatorId 操作人
     */
    public void cancel(RunSession session, Long operatorId) {
        if (session == null || session.getId() == null || flight.running(session.getId())) {
            return;
        }
        if (!RunStatus.RUNNING.name().equals(session.getStatus())) {
            return;
        }
        session.setErrorSummary("已取消");
        session.setStatus(RunStatus.CANCELLED.name());
        session.setEndTime(LocalDateTime.now());
        long operator = operatorId != null ? operatorId : 0L;
        sessions.updateOutcome(session, operator);
        obs.cancel(session.getId());
        memory.onClosed(session);
    }

    /**
     * 删除仍在 RUNNING 的会话前批抽 LONG。与空闲 / 取消共用 onClosed → onSessionEnd 幂等路径。
     * 本类 NOT_SUPPORTED：不挂在删除的业务事务里调模型。
     * 不改 status / ended_at；失败不抛到删除。
     *
     * @param session 删除前快照，须仍是 RUNNING
     */
    public void extractBeforeDelete(RunSession session) {
        if (session == null || session.getId() == null) {
            return;
        }
        if (flight.running(session.getId())) {
            log.info("删除前批抽跳过 reason=in_flight sessionId={}", session.getId());
            return;
        }
        if (!RunStatus.RUNNING.name().equals(session.getStatus())) {
            log.info("删除前批抽跳过 reason=not_running sessionId={} status={}", session.getId(), session.getStatus());
            return;
        }
        log.info("删除前批抽开始 extractBeforeDelete sessionId={} agentId={} userId={}",
                session.getId(), session.getAgentId(), session.getUserId());
        memory.onClosed(session);
        log.info("删除前批抽结束 extractBeforeDelete sessionId={}", session.getId());
    }

    /**
     * 空闲到期：标 SUCCESS + ended_at，不抽模型。
     */
    private void closeIdle(RunSession session, LocalDateTime now) {
        session.setStatus(RunStatus.SUCCESS.name());
        session.setEndTime(now);
        session.setErrorSummary("空闲自动结束");
        sessions.updateOutcome(session, 0L);
        log.info("空闲收口终态 sessionId={} status=SUCCESS", session.getId());
    }

    private void sleepBetween() {
        long ms = memoryProperties.getLongExtractIntervalMs();
        if (ms <= 0L) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private int idleSec(Long agentId) {
        if (agentId == null) {
            return 0;
        }
        Integer sec = policies.effective(agentId).getCtxIdleTtlSec();
        return sec == null ? 0 : sec;
    }
}

package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.CauseDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 把「进程已经不在跑、库里仍标着 RUNNING」的会话收成取消。所属域：runtime。
 * <p>
 * 现网多轮期间会话一直停在 {@link RunStatus#RUNNING}，进程被杀、请求线程来不及走
 * {@code fail()} 时，这一行不会自己变成终态。左栏是否转圈不直接读这个状态，
 * 但未收尾的一轮会让重进续订；这里先把遗留行收成 {@link RunStatus#CANCELLED}
 * （与用户点取消同一终态，不新造状态），并顺手把该会话仍开着的 trace 收成取消。
 * <p>
 * 不删会话、不删消息、不改标题和预览，也不抽长期记忆（启动路径禁止出站模型）。
 * 正在单飞或内存快照里的会话一律跳过，避免把启动之后新发起的一轮误标中断。
 */
@Service
public class StaleRunCloser {

    private static final Logger log = LoggerFactory.getLogger(StaleRunCloser.class);

    private final RunSessionRepository sessions;
    private final TrialFlight flight;
    private final TrialLiveBuffer live;
    private final TrialObsHooks obs;

    /**
     * @param sessions 会话；条件更新保证只改仍是 RUNNING 且更新时间没被推进的行
     * @param flight   本进程正在执行的一轮；启动扫描必须让开
     * @param live     本进程内存里的进行中快照；有快照说明这轮还在本进程里跑
     * @param obs      把该会话尚未结束的 trace / 步骤收成 CANCELLED；失败不影响会话终态
     */
    public StaleRunCloser(RunSessionRepository sessions, TrialFlight flight,
                          TrialLiveBuffer live, TrialObsHooks obs) {
        this.sessions = sessions;
        this.flight = flight;
        this.live = live;
        this.obs = obs;
    }

    /**
     * 启动扫一遍仍是 RUNNING 的会话。本进程里正在跑的跳过。
     * 单行失败不影响其余行，也不抛给启动流程。
     *
     * @return 本次真正改成 CANCELLED 的行数
     */
    public int recoverAfterRestart() {
        List<RunSession> running;
        try {
            running = sessions.listRunning(null, null);
        } catch (RuntimeException ex) {
            log.warn("启动收口读运行中会话失败 type={} cause={}",
                    ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            return 0;
        }
        int changed = 0;
        for (RunSession session : running) {
            if (session == null || session.getId() == null) {
                continue;
            }
            // 启动后已经有请求占上了这行：让它自己跑完，不要收成中断
            if (busy(session.getId())) {
                log.info("启动收口跳过 reason=in_flight sessionId={}", session.getId());
                continue;
            }
            try {
                if (markInterrupted(session)) {
                    changed++;
                }
            } catch (RuntimeException ex) {
                log.warn("启动收口失败 sessionId={} type={} cause={}",
                        session.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            }
        }
        return changed;
    }

    /**
     * 进程正在退出：只收本进程还占着单飞的会话。
     * 轮与轮之间本来就是 RUNNING、但此刻没有请求的行，留给下次启动扫描，避免停机时把正常多轮改掉。
     *
     * @return 本次真正改成 CANCELLED 的行数
     */
    public int recoverOnShutdown() {
        int changed = 0;
        for (Long sessionId : flight.snapshot()) {
            try {
                Optional<RunSession> found = sessions.findById(sessionId);
                if (found.isEmpty()) {
                    continue;
                }
                if (markInterrupted(found.get())) {
                    changed++;
                }
            } catch (RuntimeException ex) {
                log.warn("停机收口失败 sessionId={} type={} cause={}",
                        sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            }
        }
        return changed;
    }

    /**
     * 一轮没有走到成功或失败收尾就逃出了执行（fail 自己抛错、Error、线程被掐断）。
     * 此时单飞已经释放；若这行仍是 RUNNING 且没有别的请求占上，收成取消。
     * 正常的业务失败已经写了 error 事件并保持 RUNNING，不会进这里。
     *
     * @param sessionId 没能正常收尾的会话
     */
    public void abandonIfIdle(Long sessionId) {
        if (sessionId == null || busy(sessionId)) {
            if (sessionId != null) {
                log.info("异常收口跳过 reason=in_flight sessionId={}", sessionId);
            }
            return;
        }
        try {
            sessions.findById(sessionId).ifPresent(this::markInterrupted);
        } catch (RuntimeException ex) {
            log.warn("异常收口失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
        }
    }

    /**
     * 条件更新成功后才改内存对象，并收掉仍开着的 trace。
     * 条件没中（这行已被新请求推进）就当没发生。
     *
     * @param session 扫到或查到的会话，须仍是 RUNNING
     * @return true 表示库里的这一行已经改成 CANCELLED
     */
    private boolean markInterrupted(RunSession session) {
        if (session == null || session.getId() == null) {
            return false;
        }
        if (!RunStatus.RUNNING.name().equals(session.getStatus())) {
            return false;
        }
        LocalDateTime endedAt = LocalDateTime.now();
        boolean updated = sessions.interruptIfUnchanged(session.getId(), session.getUpdateTime(), endedAt);
        if (!updated) {
            log.info("收口未命中 reason=row_moved sessionId={}", session.getId());
            return false;
        }
        session.setStatus(RunStatus.CANCELLED.name());
        session.setEndTime(endedAt);
        // trace 收口失败不能把会话终态打回去：行已经是 CANCELLED
        try {
            obs.cancel(session.getId());
        } catch (RuntimeException ex) {
            log.warn("收口 trace 失败 sessionId={} type={} cause={}",
                    session.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
        }
        log.info("运行中会话已收成取消 sessionId={} status=CANCELLED", session.getId());
        return true;
    }

    /**
     * 本进程里这一轮还在跑：单飞或内存快照任一占着都算。
     *
     * @param sessionId 会话主键
     * @return true 表示不能收口
     */
    private boolean busy(Long sessionId) {
        return flight.running(sessionId) || live.running(sessionId);
    }
}

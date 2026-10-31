package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;

import java.time.LocalDateTime;

/**
 * 进行中的会话是否已经空闲到可以收口并批抽 LONG。所属域：runtime。
 * 时钟用 updateTime，没有则用 startTime。时长来自策略的 ctx_idle_ttl_sec，不另加列。
 * 本类只做到期判定；收口终态由 TrialSessionCloser 负责。
 */
public final class SessionIdle {

    private SessionIdle() {
    }

    /**
     * @param session  会话
     * @param idleSec  空闲秒数，小于等于 0 视为不抽
     * @param now      当前时间
     * @param inFlight 本轮是否还在执行
     * @return 仍是 RUNNING、没有在跑，且上次更新已超过空闲秒数
     */
    public static boolean due(RunSession session, int idleSec, LocalDateTime now, boolean inFlight) {
        if (inFlight || session == null || now == null || idleSec <= 0) {
            return false;
        }
        if (!RunStatus.RUNNING.name().equals(session.getStatus())) {
            return false;
        }
        LocalDateTime touched = session.getUpdateTime() != null ? session.getUpdateTime() : session.getStartTime();
        if (touched == null) {
            return false;
        }
        return !touched.plusSeconds(idleSec).isAfter(now);
    }
}

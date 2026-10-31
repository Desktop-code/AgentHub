package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;

/**
 * 一轮试跑落库时会话应保持的状态。所属域：runtime。
 * 多轮期间不写终态、不写结束时间。取消才写 CANCELLED；空闲只批抽不结案。
 */
public final class TrialTurnOutcome {

    private TrialTurnOutcome() {
    }

    /**
     * 把会话留在进行中。调用方继续更新轮次、标题和本轮错误摘要。
     * 内存里清掉 endTime，避免脏对象把旧终态带进 updateOutcome；
     * 仓储对 null endTime 不写列，只刷新 updated_at。
     *
     * @param session 本轮所属会话
     */
    public static void keepOpen(RunSession session) {
        session.setStatus(RunStatus.RUNNING.name());
        session.setEndTime(null);
    }
}

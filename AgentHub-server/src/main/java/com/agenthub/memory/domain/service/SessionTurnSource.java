package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.ContextTurn;

import java.util.List;

/**
 * 窗口 miss 时的耐久来源。所属域：memory。
 * 由 runtime 用 agent_run_event 实现。memory 不依赖试跑引擎。
 */
public interface SessionTurnSource {

    /**
     * @param sessionId 会话
     * @return user / assistant 轮次，按发生顺序
     */
    List<ContextTurn> replay(long sessionId);
}

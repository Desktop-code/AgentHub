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
     * @return user / assistant 轮次，按发生顺序。用户轮只有原话，不含附件正文
     */
    List<ContextTurn> replay(long sessionId);

    /**
     * 给对话模型回放的轮次。长期记忆抽取仍用 {@link #replay}，避免把整篇附件当成用户偏好。
     *
     * @param sessionId 会话
     * @return user / assistant 轮次。用户轮在原话后附上已解析的附件正文（若当时写过）
     */
    default List<ContextTurn> replayForModel(long sessionId) {
        return replay(sessionId);
    }
}

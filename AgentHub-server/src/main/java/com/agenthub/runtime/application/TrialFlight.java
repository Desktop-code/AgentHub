package com.agenthub.runtime.application;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一试跑会话的单飞。所属域：runtime。同步执行期间拒绝第二轮和取消。
 */
@Component
public class TrialFlight {

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    /**
     * @param sessionId 会话
     * @return 抢到执行权时为 true
     */
    public boolean tryStart(Long sessionId) {
        return running.add(sessionId);
    }

    /**
     * @param sessionId 会话
     */
    public void finish(Long sessionId) {
        running.remove(sessionId);
    }

    /**
     * @param sessionId 会话
     * @return 正在执行时为 true
     */
    public boolean running(Long sessionId) {
        return running.contains(sessionId);
    }
}

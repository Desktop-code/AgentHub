package com.agenthub.eval.application;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评测跑批进行中的 in-flight 试跑会话登记。所属域：eval 的 application。
 * <p>用途：cancel 到达时能找到「当前正在跑的 trial session」并经 {@code TrialEvalPort.cancelSession} 级联取消。
 * <p>为什么不写库：session 生命周期短于 case，落库会跟 result 行抢写；内存表足够 P0 单实例。
 * 多实例时再换 Redis（本期不做）。
 * <p>线程：ConcurrentHashMap，可并发 bind/clear；同一 run 串行执行，不存在双绑竞态产品需求。
 */
@Component
public class EvalRunFlight {

    private final ConcurrentHashMap<Long, String> currentSessionByRun = new ConcurrentHashMap<>();

    /**
     * 登记某跑批当前 in-flight 会话（覆盖旧值）。
     *
     * @param runId     跑批
     * @param sessionId 试跑会话 id
     */
    public void bind(Long runId, String sessionId) {
        if (runId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        currentSessionByRun.put(runId, sessionId);
    }

    /**
     * @param runId 跑批
     * @return 当前会话，可空
     */
    public Optional<String> current(Long runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(currentSessionByRun.get(runId));
    }

    /**
     * 清掉登记（case 结束或跑批收尾时调用）。
     *
     * @param runId 跑批
     */
    public void clear(Long runId) {
        if (runId != null) {
            currentSessionByRun.remove(runId);
        }
    }
}

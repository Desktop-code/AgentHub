package com.agenthub.runtime.application;

import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.TraceRecorder;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.TraceStart;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.service.CauseDigest;
import org.springframework.stereotype.Component;

/**
 * 试跑和调用追踪的衔接。所属域：runtime。
 * 埋点失败不改变试跑结果。这里只开关 trace，步骤树由 Router、模型和工具回调写入。
 */
@Component
public class TrialObsHooks {

    private final TraceRecorder recorder;

    /**
     * @param recorder 可观测写入。失败已在实现里吞掉
     */
    public TrialObsHooks(TraceRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * 一条用户消息对应一条 RUNNING trace，并绑到当前线程。
     *
     * @param session 试跑会话
     * @param title   用户原文
     * @return 打开成功时的范围；失败时为 null
     */
    public ObsTraceScope begin(RunSession session, String title) {
        return recorder.beginConsole(new TraceStart(session.getId(), session.getAgentId(), session.getUserId(), title))
                .map(scope -> {
                    ObsTraceHolder.bind(scope);
                    return scope;
                })
                .orElse(null);
    }

    /**
     * @param scope 本轮 trace，可空
     */
    public void finishOk(ObsTraceScope scope) {
        if (scope != null) {
            recorder.finish(scope, ObsStatus.OK, null);
        }
    }

    /**
     * @param scope  本轮 trace，可空
     * @param reason 失败说明，入库前会打码
     */
    public void finishError(ObsTraceScope scope, String reason) {
        finishError(scope, reason, null);
    }

    /**
     * @param scope        本轮 trace，可空
     * @param reason       失败说明，入库前会打码
     * @param finishReason 可空；撞限时写 timeout / max_rounds 等
     */
    public void finishError(ObsTraceScope scope, String reason, String finishReason) {
        finishError(scope, reason, finishReason, null);
    }

    /**
     * 同 {@link #finishError(ObsTraceScope, String, String)}，另写一份脱敏 cause 到 trace 扩展信息。
     *
     * <p>为什么需要：trace 是面板最外层那一行。失败时只有一句人话，
     * 排查「连通测成功、试跑失败」就还得逐层展开步骤；把 cause 提到 trace 级一眼可见。
     *
     * @param cause 失败时的原始异常，可空；<b>调用方保证已脱敏</b>（本方法再经 ObsTexts 打一遍）
     */
    public void finishError(ObsTraceScope scope, String reason, String finishReason, Throwable cause) {
        if (scope == null) {
            return;
        }
        // attrs 只在真有 cause 时才写：为空时 ObsAttrs.withCause 返回 null，不会污染既有 attrs
        recorder.finish(scope, ObsStatus.ERROR, reason, finishReason,
                ObsAttrs.withCause(null, CauseDigest.of(cause)));
    }

    /**
     * 请求结束时丢掉线程上的 trace，避免线程池串到下一轮。
     */
    public void clear() {
        ObsTraceHolder.clear();
    }

    /**
     * 会话取消时，把尚未结束的 trace 和步骤改成 CANCELLED。
     *
     * @param sessionId 试跑会话
     */
    public void cancel(long sessionId) {
        recorder.cancelSession(sessionId, "已取消");
    }
}

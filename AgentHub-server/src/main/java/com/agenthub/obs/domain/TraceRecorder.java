package com.agenthub.obs.domain;

import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanDraft;
import com.agenthub.obs.domain.model.TraceStart;

import java.util.Optional;

/**
 * 试跑埋点入口。所属域：obs。
 * 实现必须吞掉自己的失败，让主调用和计费继续。一期 seq 在同一条 trace 上串行递增。
 * 树键为自增 id + parent_id；open 返回行主键。
 */
public interface TraceRecorder {

    /**
     * 用户发出一条控制台消息时打开 trace，状态为 RUNNING。
     *
     * @param start 会话、用户和问题原文
     * @return 打开成功时的范围；会话或用户缺失、或写入失败时为空
     */
    Optional<ObsTraceScope> beginConsole(TraceStart start);

    /**
     * 追加一个 RUNNING 步骤。TASK 会被丢掉。
     *
     * @param scope 当前 trace
     * @param draft 类型、父子和可选输入
     * @return 新行主键；失败时为 null
     */
    Long openSpan(ObsTraceScope scope, SpanDraft draft);

    /**
     * 把仍在 RUNNING 的步骤收成终态，并写入分列正文与专用元数据。
     *
     * @param scope  当前 trace
     * @param spanId 行主键
     * @param close  终态、input/output 与 LLM/TOOL 等列
     */
    void closeSpan(ObsTraceScope scope, Long spanId, SpanClose close);

    /**
     * 结束整条 trace，并把还没结束的步骤收成同一终态；回写次数与 token 汇总。
     *
     * @param scope        当前 trace
     * @param status       终态
     * @param errorSummary 失败或取消说明，可空
     */
    void finish(ObsTraceScope scope, ObsStatus status, String errorSummary);

    /**
     * 同 {@link #finish(ObsTraceScope, ObsStatus, String)}，可显式写入 finish_reason（如 timeout、max_rounds）。
     *
     * @param scope        当前 trace
     * @param status       终态
     * @param errorSummary 失败或取消说明，可空
     * @param finishReason 可空；空则按 status 推导
     */
    default void finish(ObsTraceScope scope, ObsStatus status, String errorSummary, String finishReason) {
        finish(scope, status, errorSummary);
    }

    /**
     * 会话被取消时，把该会话下仍为 RUNNING 的 trace 和步骤改成 CANCELLED。
     *
     * @param sessionId 试跑会话
     * @param reason    说明，可空
     */
    void cancelSession(long sessionId, String reason);
}

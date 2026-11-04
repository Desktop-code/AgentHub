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
     * 为一次编排运行（工作流 run）打开根 trace：channel=WORKFLOW，落 {@code workflow_run_id} 与编排扩展信息。
     *
     * <p>默认空实现：保证既有控制台埋点实现与测试桩不需要改动；正式实现见
     * {@code TraceRecorderAppService}。没有对话会话时由调用方传约定占位 sessionId。
     *
     * @param start          标题、用户；sessionId 为占位值
     * @param workflowRunId  workflow_run.id 字符串，写入 obs_trace.workflow_run_id
     * @param attrsJson      编排队列扩展信息（已脱敏），可空
     * @return 打开成功时的范围；实现未接线或写入失败时为空
     */
    default Optional<ObsTraceScope> beginWorkflow(TraceStart start, String workflowRunId, String attrsJson) {
        return Optional.empty();
    }

    /**
     * 暂停后恢复调度时，按已有 traceId 重新取回范围，seq / step_no 从库里已用的最大号接续。
     *
     * <p>默认空实现，理由同 {@link #beginWorkflow(TraceStart, String, String)}。
     *
     * @param traceId 业务 traceId
     * @return 可继续开步的范围；trace 不存在、已终态或实现未接线时为空
     */
    default Optional<ObsTraceScope> resumeWorkflow(String traceId) {
        return Optional.empty();
    }

    /**
     * 追加一个 RUNNING 步骤。工作流编排节点用 TASK。
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
     * 同 {@link #finish(ObsTraceScope, ObsStatus, String, String)}，并写入 trace 级扩展信息。
     *
     * <p>为什么 trace 级也要一份：面板上整次提问是最外层一行，失败时若只写 error_summary，
     * 看到的就是一句人话文案；把脱敏 cause 写进 trace.attrs_json，排障不必先展开每一层步骤。
     *
     * @param scope        当前 trace
     * @param status       终态
     * @param errorSummary 已打码的失败说明，可空
     * @param finishReason 可空；空则按 status 推导
     * @param attrsJson    扩展 JSON（已脱敏），可空
     */
    default void finish(ObsTraceScope scope, ObsStatus status, String errorSummary, String finishReason,
                        String attrsJson) {
        finish(scope, status, errorSummary, finishReason);
    }

    /**
     * 会话被取消时，把该会话下仍为 RUNNING 的 trace 和步骤改成 CANCELLED。
     *
     * @param sessionId 试跑会话
     * @param reason    说明，可空
     */
    void cancelSession(long sessionId, String reason);
}

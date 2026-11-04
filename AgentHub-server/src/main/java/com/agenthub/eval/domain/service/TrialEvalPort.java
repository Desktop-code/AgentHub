package com.agenthub.eval.domain.service;

import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.EvalTrialRequest;

/**
 * 评测侧调用现网试跑的<strong>唯一</strong>出站防腐口。所属域：eval 的 domain。
 * <p>调用方：{@code EvalRunnerAppService}；实现方：infra 的 {@code TrialEvalPortImpl}，
 * <b>只</b>依赖 runtime.application 门面（{@code TrialSessionAppService} /
 * {@code TrialRunAppService}），禁止直调 Executor / ReAct / LLM / 自开 HTTP 打 8080。
 * <p>线程：实现可阻塞（同步跑完一轮）；Runner 串行调用，禁止同一 case 并发两次 {@link #runCase}。
 * <p>重试：session 级 ≤1（硬规范）；失败由 Runner 记 ERROR，Impl 不再外包一层重试。
 */
public interface TrialEvalPort {

    /**
     * 跑一个 case：创建 EVAL 会话 → 发消息 → 等终态 → 抽最小 {@link EvalArtifact}。
     * <p>业务失败（Agent 未启用、模型拒绝等）应落进 artifact.status=FAILED + errorSummary，
     * <b>不要</b>把可预期业务拒绝一律抛成未捕获异常；基础设施级异常（超时、门面不可用）可抛，
     * 由 Runner 收成 verdict=ERROR。
     *
     * @param request 入参（message / timeout / eval_run_id / case_key 等）
     * @return 产物摘要；sessionId 应尽量有值，便于 cancel 级联
     */
    EvalArtifact runCase(EvalTrialRequest request);

    /**
     * 取消现网试跑会话（走门面 cancel）。已终态或不存在时实现可吞掉业务冲突，避免拖垮跑批收尾。
     *
     * @param sessionId 试跑会话 id（字符串，与 artifact.sessionId 同形）
     */
    void cancelSession(String sessionId);
}

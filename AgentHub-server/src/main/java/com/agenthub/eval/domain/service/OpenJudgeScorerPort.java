package com.agenthub.eval.domain.service;

import com.agenthub.eval.domain.model.OpenJudgeRequest;
import com.agenthub.eval.domain.model.OpenJudgeScoreResult;

/**
 * OpenJudge 软评分防腐口。所属域：eval 的 domain。
 * <p>调用方：{@code EvalRunnerAppService}（RuleScorer 之后）。
 * 实现方：infra {@code OpenJudgeScorerAdapter}（进程外 HTTP sidecar）。
 * <p>约束：零 JVM 内 LLM；失败只返回 {@link OpenJudgeScoreResult} 状态，不抛垮 run；
 * <b>绝不</b>改写 gate_passed / 把 case 打成 FAIL。
 * 线程：实现须无状态或自行线程安全。
 */
public interface OpenJudgeScorerPort {

    /**
     * 对单 case 产物做软评分。
     *
     * @param request 入参（含 artifact / graders / baseUrl）
     * @return 评分结果；不可用时 status=SKIPPED 或 ERROR，不抛业务门禁异常
     */
    OpenJudgeScoreResult score(OpenJudgeRequest request);
}

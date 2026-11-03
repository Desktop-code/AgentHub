/**
 * 自动评测域。所属域：eval。
 * <p>已交付：
 * <ul>
 *   <li>刀1 CaseStore；刀2a 跑批壳；刀2b Trial+Runner；刀3 RuleScorer 硬门禁；</li>
 *   <li>刀4：{@link com.agenthub.eval.domain.service.OpenJudgeScorerPort} HTTP 软评分 +
 *       {@link com.agenthub.eval.application.ReportBuilder} + {@code GET .../report}。
 *       OpenJudge <b>不</b>翻转 gate_passed；失败只用 openjudge_status=ERROR|SKIPPED。</li>
 * </ul>
 * <p>刻意仍不包含：控制台菜单 / 坏案回流 / SSE（刀5）、EvalScope 桥（刀6）。
 */
package com.agenthub.eval;

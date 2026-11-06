package com.agenthub.runtime.application.router;

/**
 * 被挡下的候选与原因。所属域：runtime。
 *
 * <p>「挡下」包含两种：目标 Agent 未开 allow_orchestrated，或运行模式不被本编排允许。
 * 这类候选不参与判路由，但必须在时间线留「已跳过」，因此与 {@link Candidate} 分开存放。
 *
 * @param candidate 路由行 + 子 Agent
 * @param runMode   子的运行模式（ROUTER 之外只有 REACT / PLAN_AND_EXECUTE）
 * @param reason    中文原因，进时间线
 */
public record BlockedCandidate(Candidate candidate, String runMode, String reason) {
}

package com.agenthub.runtime.application.router;

import com.agenthub.runtime.domain.model.TrialTurn;

/**
 * 目标方案（另一套编排方案）内部的一跳。所属域：runtime。
 *
 * <p>为什么需要它：当入口把问题分给「方案」时，方案内部还会把自己的子专家各跑一遍。
 * 这些子专家的「路由到」行与思考以前只被压成一条「子编排内部：…」的 SYSTEM 说明，
 * 时间线上看不出到底哪些子 Agent 参与了。本记录把那一条说明还原成结构化的一跳，
 * 由时间线写入器紧随「路由到 {方案}」之后逐跳落库，前端即可按块展示。
 *
 * <p>只承载展示所需的三件事：分给了谁（{@code routeLine}）、它自己想了什么
 * （{@code turn.traces}）、失败原因。不改路由结果，也不参与终答拼接。
 *
 * @param routeLine 「路由到 {子展示名}（key），依据：…」；不含密钥
 * @param turn      该子在目标方案里的执行结果；失败 / 跳过时可空
 * @param error     失败摘要；成功为 null
 */
public record NestedRoute(String routeLine, TrialTurn turn, String error) {
}

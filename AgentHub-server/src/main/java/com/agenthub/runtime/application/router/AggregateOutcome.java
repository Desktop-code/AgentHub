package com.agenthub.runtime.application.router;

import com.agenthub.runtime.domain.model.TrialTurn;

import java.util.List;

/**
 * 终答汇总结果。所属域：runtime。
 *
 * @param turn        最终答复（含 token / 产物汇总）
 * @param notices     需要落 SYSTEM 事件的中文说明（VOTE 降级 / 跳过投票等）
 * @param segmentLine 需要进时间线的汇总结论行；无则 null（VOTE 才有）
 * @param winnerIndex 赢家在 outcomes 中的下标（用于给子段打「★ 选中」）；-1＝无赢家
 */
public record AggregateOutcome(TrialTurn turn, List<String> notices, String segmentLine, int winnerIndex) {
}

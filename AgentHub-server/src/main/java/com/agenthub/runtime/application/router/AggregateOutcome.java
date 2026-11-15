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
 * @param conclusionName 真汇总记录名（方案名）；只有「汇总模型真的开始了」才非空
 * @param reasoning   汇总模型自己的推理；随「方案结论：X」一起落库，刷新后思考框仍在
 */
public record AggregateOutcome(TrialTurn turn, List<String> notices, String segmentLine, int winnerIndex,
                               String conclusionName, String reasoning) {

    /**
     * 兼容构造：没有真汇总（CONCAT / VOTE / 降级）时两个新字段为空。
     *
     * @param turn        最终答复
     * @param notices     说明
     * @param segmentLine 汇总结论行
     * @param winnerIndex 赢家下标
     */
    public AggregateOutcome(TrialTurn turn, List<String> notices, String segmentLine, int winnerIndex) {
        this(turn, notices, segmentLine, winnerIndex, null, null);
    }
}

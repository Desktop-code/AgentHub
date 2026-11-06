package com.agenthub.runtime.application.router;

import java.util.List;

/**
 * 投票选优结论。所属域：runtime。
 *
 * @param winner      赢家子结果；null 表示投票失败、已降级为拼接
 * @param degraded    是否发生降级
 * @param notices     进 SYSTEM 事件的说明
 * @param segmentLine 进时间线的结论行
 */
public record VotePick(ChildOutcome winner, boolean degraded, List<String> notices, String segmentLine) {

    /**
     * 投票失败（降级拼接）的统一构造。
     *
     * @param note 中文说明
     * @return 无赢家 + 已降级
     */
    public static VotePick failed(String note) {
        return new VotePick(null, true, List.of(note), note);
    }
}

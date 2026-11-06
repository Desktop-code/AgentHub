package com.agenthub.runtime.application.router;

/**
 * 一次子执行意图。所属域：runtime。
 *
 * <p>六套模板最终都归一到「一组 intents」再派发，因此这一类型是模板与子执行之间的契约。
 *
 * @param candidate 命中的候选（路由行 + 子 Agent）
 * @param subQuery  给该子的子问题（固定模板即用户原问全文）
 * @param reason    判定依据（固定模板写「固定并行 / 固定串行」）
 * @param routeLine 思考/泳道用的本子标题；null 时按判路由语义生成「路由到…」
 */
public record RoutedIntent(Candidate candidate, String subQuery, String reason, String routeLine) {

    /**
     * 不带自定义标题的意图：标题由上层按判路由语义补。
     *
     * @param candidate 命中的候选
     * @param subQuery  子问题
     * @param reason    判定依据
     */
    public RoutedIntent(Candidate candidate, String subQuery, String reason) {
        this(candidate, subQuery, reason, null);
    }
}

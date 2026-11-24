package com.agenthub.runtime.application.router;

import com.agenthub.runtime.domain.model.ParticipantView;

/**
 * 一轮编排的判定 / 汇总推理暂存。所属域：runtime。
 *
 * <p>从 {@code RouterTrialExecutor} 搬出：判路由与汇总都不把推理放进返回值，而是流式推给页面；
 * 入口在执行开始时换上一份新的暂存，判路由、汇总、模板分支各自往里写，退出时整体交给返回值落库。
 * 因为它同时被 {@link RouterOutcomeAssembler}（真汇总结论）与模板读取，类型与线程级持有一起搬到本包。
 *
 * <p>只保存文本与轻量身份，不保存模型响应对象；不参与任何路由 / 汇总决策。
 *
 * <p>并发注意：方案嵌套可能以 CallerRuns 内联跑在同一线程上，因此进入时必须保存旧值、
 * 退出时恢复，不能无条件清理（否则会把外层的暂存打掉）。
 */
public final class TurnNotes {

    /** 当前线程本轮暂存；入口 swap 进入、finally restore 退出 */
    private static final ThreadLocal<TurnNotes> CURRENT = new ThreadLocal<>();

    /** 判路由模型这一轮的推理（入口块 / 方案分流块的思考） */
    public final StringBuilder judgeReasoning = new StringBuilder();

    /** 真汇总记录名（方案名）；没有真汇总时为 null */
    public String conclusionName;

    /** 最外层入口身份；嵌套方案不写 */
    public ParticipantView gateway;

    /** 汇总模型这一轮的推理 */
    public final StringBuilder conclusionReasoning = new StringBuilder();

    /**
     * 本轮是否允许唯一叶子输出可点选项块（工作台开启；嵌套方案 / 调试 / 试跑为 false）。
     *
     * <p>挂在线程上下文里而不是一路加参数：入口、判路由、汇总、模板分支的签名保持不动，
     * 只有真正装配叶子的那一处读它；嵌套方案会新开一份暂存，天然不会继承外层开关。
     */
    public boolean leafChoice;

    /**
     * 换上一份新的本轮暂存。
     *
     * @param next 新的暂存；不应为 null
     * @return 换入前的旧值（可能为 null），必须原样交给 {@link #restore(TurnNotes)}
     */
    public static TurnNotes swap(TurnNotes next) {
        TurnNotes previous = CURRENT.get();
        CURRENT.set(next);
        return previous;
    }

    /**
     * 还原到进入本轮之前的状态。
     *
     * @param previous {@link #swap(TurnNotes)} 的返回值；null 表示进入前没有暂存，直接清理线程本地值
     */
    public static void restore(TurnNotes previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /**
     * @return 当前线程本轮暂存；不在编排轮内时为 null
     */
    public static TurnNotes current() {
        return CURRENT.get();
    }
}

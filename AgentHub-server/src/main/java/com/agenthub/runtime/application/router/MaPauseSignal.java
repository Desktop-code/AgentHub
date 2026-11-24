package com.agenthub.runtime.application.router;

import com.agenthub.runtime.domain.model.MaPauseSnapshot;

/**
 * 「方案在子专家处等用户」的控制流信号。所属域：runtime。
 *
 * <p>为什么用异常而不是返回值：停等发生在串行循环的中间，调用链上还有多层模板 / 日志 /
 * 汇总。若逐层返回「暂停」标记，每条路径都要改签名并判断，容易漏；用一个**不建栈**的
 * 专用异常直接穿到 {@code TrialRunAppService}，在入口一次接住并落库，其它模板完全无感。
 *
 * <p>禁止把本信号当成失败：所有 {@code catch (RuntimeException)} 的收口都要先放行它，
 * 否则会先写失败说明、再被外层当成停等，时间线里多出一条假错误。
 */
public class MaPauseSignal extends RuntimeException {

    private final MaPauseSnapshot state;

    /**
     * @param state 停在谁、问了什么、哪些还没跑
     */
    public MaPauseSignal(MaPauseSnapshot state) {
        // 控制流异常：不建栈，避免每次询问都刷一段无意义的堆栈
        super("方案在子专家处等待用户回复", null, false, false);
        this.state = state;
    }

    /**
     * @return 停等快照
     */
    public MaPauseSnapshot state() {
        return state;
    }
}

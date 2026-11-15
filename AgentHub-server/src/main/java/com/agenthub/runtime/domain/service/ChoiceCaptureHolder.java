package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.ChoiceCapture;

/**
 * 把本轮「用户拍板选项」的采集槽交给装配与执行链路。所属域：runtime。
 *
 * <p>为什么用线程上下文而不是给 {@code ReActTrialExecutor#execute} 加参数：
 * 那是三处入口（单专家 / Router 子 / 续作）共用的公开签名，加参数会波及全部装配桩；
 * 而选项能力只由工作台侧决定，天然是请求级上下文。
 *
 * <p>线程边界（重要）：
 * <ul>
 *   <li>本 ThreadLocal 只在<b>请求线程</b>读写：{@code TrialRunAppService} 放槽、装配时取槽、
 *       整轮结束后读结果；</li>
 *   <li>工具线程<b>不读</b>本 ThreadLocal：工具实例持有的是同一个 {@link ChoiceCapture} 引用，
 *       所以 boundedElastic 上执行也能写回同一个对象。</li>
 * </ul>
 * 单意图叶子是内联执行（不开池），因此装配时一定能在同一线程取到槽；
 * 多意图 / 嵌套 / 计划执行不安装选项工具，取不到也不影响既有行为。
 *
 * <p>必须在请求结束时 {@link #clear()}，否则线程池会把上一轮的槽带到下一轮。
 */
public final class ChoiceCaptureHolder {

    private static final ThreadLocal<ChoiceCapture> CURRENT = new ThreadLocal<>();

    private ChoiceCaptureHolder() {
    }

    /**
     * 开始采集：为本轮放一个空槽。
     *
     * @return 新建的槽；调用方持有它，整轮结束后读结果
     */
    public static ChoiceCapture begin() {
        ChoiceCapture capture = new ChoiceCapture();
        CURRENT.set(capture);
        return capture;
    }

    /**
     * @return 当前线程的采集槽；本轮不允许拍板时为 null
     */
    public static ChoiceCapture current() {
        return CURRENT.get();
    }

    /**
     * 本轮结束（无论成败）调用，避免槽跟着池线程串到下一轮。
     */
    public static void clear() {
        CURRENT.remove();
    }
}

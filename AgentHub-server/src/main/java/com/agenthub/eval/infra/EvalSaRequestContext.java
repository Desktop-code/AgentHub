package com.agenthub.eval.infra;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 评测试跑鉴权上下文透传。所属域：eval 的 infra。
 * <p>现网 Sa-Token（{@code SaTokenContextForSpringInJakartaServlet}）依赖
 * {@link RequestContextHolder} 取 HttpServletRequest；该 ThreadLocal <b>不可继承</b>。
 * {@link TrialEvalPortImpl} 为超时把 {@code send} 丢到 worker 线程时必须显式恢复，
 * 否则 {@code OperatorContext.requireUserId()} 抛「未登录或登录已过期」。
 * <p>约定：在仍处请求线程时 {@link #capture()}；worker 内 {@link #runWith}；
 * {@code finally} 清 ThreadLocal，避免线程池复用串请求。
 * <p>复用 Spring 现成 {@link RequestContextHolder}，不引入 TTL / 第二套鉴权框架。
 */
public final class EvalSaRequestContext {

    private EvalSaRequestContext() {
    }

    /**
     * 在请求线程捕获当前 Web 属性；无 Web 上下文时返回 null（调用方应拒绝开跑）。
     *
     * @return 可跨线程恢复的快照；可能为 null
     */
    public static RequestAttributes capture() {
        return RequestContextHolder.getRequestAttributes();
    }

    /**
     * 入口无登录 Web 上下文时立刻失败，避免建会话后再在 worker 上报「未登录」。
     *
     * @param attrs {@link #capture()} 结果
     * @throws BizException UNAUTHORIZED
     */
    public static void requirePresent(RequestAttributes attrs) {
        if (attrs == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 在 worker 线程恢复请求属性并执行任务；结束时 {@link RequestContextHolder#resetRequestAttributes()}。
     *
     * @param attrs 捕获的属性；null 时仍执行但鉴权会失败（由门面抛错）
     * @param task  任务
     * @param <T>   返回类型
     * @return 任务结果
     * @throws Exception 任务抛出的受检异常原样抛出
     */
    public static <T> T runWith(RequestAttributes attrs, CallableWithException<T> task) throws Exception {
        RequestAttributes previous = RequestContextHolder.getRequestAttributes();
        try {
            if (attrs != null) {
                // 非 inheritable：仅本 worker 可见，避免再派生子线程时误继承已结束的请求
                RequestContextHolder.setRequestAttributes(attrs, false);
            } else {
                RequestContextHolder.resetRequestAttributes();
            }
            return task.call();
        } finally {
            // 恢复 worker 原状（通常为空），禁止泄漏到线程池下一任务
            if (previous != null) {
                RequestContextHolder.setRequestAttributes(previous, false);
            } else {
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    /**
     * 允许抛受检异常的 Callable。所属域：eval。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    public interface CallableWithException<T> {
        /**
         * @return 结果
         * @throws Exception 业务或 IO
         */
        T call() throws Exception;
    }
}

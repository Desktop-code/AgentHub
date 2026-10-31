package com.agenthub.obs.domain;

/**
 * 当前线程上的一次调用。所属域：obs。
 * 模型流可能换线程，所以只在打开模型或收集器时取出范围对象，之后用对象本身，不再读这个 ThreadLocal。
 */
public final class ObsTraceHolder {

    private static final ThreadLocal<ObsTraceScope> CURRENT = new ThreadLocal<>();

    private ObsTraceHolder() {
    }

    /**
     * @param scope 本轮 trace。空则清掉
     */
    public static void bind(ObsTraceScope scope) {
        if (scope == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(scope);
    }

    /**
     * @return 当前线程的 trace；没有埋点时为 null
     */
    public static ObsTraceScope current() {
        return CURRENT.get();
    }

    /**
     * 请求结束时调用，避免线程池把上一次的 trace 带出去。
     */
    public static void clear() {
        CURRENT.remove();
    }
}

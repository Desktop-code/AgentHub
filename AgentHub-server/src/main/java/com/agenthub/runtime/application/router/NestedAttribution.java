package com.agenthub.runtime.application.router;

/**
 * 嵌套出站的归属闸。所属域：runtime。
 *
 * <p>子专家的增量先经过自己的包装器，再经过外层方案的包装器。
 * 内层已经声明过归属时，外层只负责把同一次调用转出去，不能再改挂成方案。
 * 计数挂在线程上：并行子线程各记各的，不会把别人的归属抢回来。
 */
final class NestedAttribution {

    /** 当前线程上，已经有几层包装器正在把这次增量归给自己 */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private NestedAttribution() {
    }

    /**
     * 以当前包装器的身份把一次增量送出去。
     *
     * <p>最外层调用（深度为 0）才声明归属，然后转发。
     * 转发途中再进到外层包装器时深度已经大于 0，只转发、不再声明。
     * 这次调用结束后深度回到 0，下一次增量仍由当时的最内层自己认领。
     *
     * @param claim   声明「这次增量是我的」；外层被挡住时不会执行
     * @param forward 真正把增量交给下游；内外层都会执行
     */
    static void emit(Runnable claim, Runnable forward) {
        if (DEPTH.get() > 0) {
            forward.run();
            return;
        }
        DEPTH.set(1);
        try {
            claim.run();
            forward.run();
        } finally {
            DEPTH.set(0);
        }
    }
}

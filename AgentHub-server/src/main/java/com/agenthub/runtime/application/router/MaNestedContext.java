package com.agenthub.runtime.application.router;

/**
 * 「另一套编排方案」作为路由目标时的执行上下文标记。所属域：runtime。
 *
 * <p>为什么需要它：命中 MA 候选后要在同一条会话里再跑一层编排，这一层的子 Agent 若继续
 * 提交到同一个 {@code routerTaskExecutor}，在池线程被占满时会出现「父等子、子等线程」的自等待。
 * 因此在嵌套执行期间打一个线程内标记，子执行改为在当前线程按序推进：
 * <b>结果与顺序不变</b>，只是不再并发抢占同一池；退出嵌套即恢复并发。
 *
 * <p>线程边界：{@link ThreadLocal}，只在命中 MA 候选的那次同步调用内有效；
 * 由执行器在 {@code finally} 里成对 enter/exit，不跨请求、不跨轮次。
 */
public final class MaNestedContext {

    /** 嵌套深度；>0 表示当前线程正在跑目标方案。 */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private MaNestedContext() {
    }

    /**
     * 进入一层嵌套。
     */
    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    /**
     * 退出一层嵌套；归零时清理线程本地值，池化线程不残留标记。
     */
    public static void exit() {
        int next = DEPTH.get() - 1;
        if (next <= 0) {
            DEPTH.remove();
            return;
        }
        DEPTH.set(next);
    }

    /**
     * @return true 表示当前线程正在执行「目标方案」的嵌套编排
     */
    public static boolean inside() {
        return DEPTH.get() > 0;
    }

    /**
     * @return 当前线程已经进入的方案层数；0 表示还在根方案（含对话入口）上
     */
    public static int depth() {
        return DEPTH.get();
    }
}

package com.agenthub.runtime.application.router;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * 「另一套编排方案」作为路由目标时的执行上下文标记。所属域：runtime。
 *
 * <p>为什么需要它：命中 MA 候选后要在同一条会话里再跑一层编排，这一层的子 Agent 若继续
 * 提交到同一个 {@code routerTaskExecutor}，在池线程被占满时会出现「父等子、子等线程」的自等待。
 * 因此在嵌套执行期间打一个线程内标记，子执行改为在当前线程按序推进：
 * <b>结果与顺序不变</b>，只是不再并发抢占同一池；退出嵌套即恢复并发。
 *
 * <p>除了深度，还要记住<b>每一层属于哪个方案</b>：参与者 id 由「深度 + 方案路由键 + 子路由键」
 * 组成，嵌套的两层方案若都分了同名子专家（如都叫 policy），只靠 routeKey 会被
 * {@code participants.putIfAbsent} 当成同一个人吞掉，第二张卡永远不出现。
 * 因此用栈保存每一层的方案键，退出一层即弹出一层。
 *
 * <p>线程边界：{@link ThreadLocal}，只在命中 MA 候选的那次同步调用内有效；
 * 由执行器在 {@code finally} 里成对 enter/exit，不跨请求、不跨轮次。
 * 嵌套内部另起的执行线程必须用 {@link #callAt(int, String, Supplier)} 显式继承，
 * 线程池不会自动传递 ThreadLocal。
 */
public final class MaNestedContext {

    /** 方案栈：栈深度即嵌套层数，栈顶是当前方案的路由键；根层为空栈。 */
    private static final ThreadLocal<Deque<String>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private MaNestedContext() {
    }

    /**
     * 进入一层嵌套（不带方案键，兼容只关心深度的调用方）。
     */
    public static void enter() {
        enter("");
    }

    /**
     * 进入一层嵌套，并记下这一层是哪个方案。
     *
     * @param parentKey 当前方案的 route_key；空串表示不知道（id 会退化为只带深度）
     */
    public static void enter(String parentKey) {
        STACK.get().push(parentKey == null ? "" : parentKey.trim());
    }

    /**
     * 退出一层嵌套；归零时清理线程本地值，池化线程不残留标记。
     */
    public static void exit() {
        Deque<String> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    /**
     * @return true 表示当前线程正在执行「目标方案」的嵌套编排
     */
    public static boolean inside() {
        return !STACK.get().isEmpty();
    }

    /**
     * @return 当前线程已经进入的方案层数；0 表示还在根方案（含对话入口）上
     */
    public static int depth() {
        return STACK.get().size();
    }

    /**
     * @return 当前层（栈顶）的方案路由键；根层或未设置时为空串
     */
    public static String parentKey() {
        String top = STACK.get().peek();
        return top == null ? "" : top;
    }

    /**
     * 在指定嵌套深度 / 父方案下执行一段逻辑，用于把上下文显式带进新线程。
     *
     * <p>为什么不依赖 InheritableThreadLocal：执行池的线程是复用的，父线程后续的
     * enter/exit 会污染池线程；这里在任务体内部设置、finally 还原，作用域严格等于这段调用。
     *
     * @param depth     进入后的嵌套深度
     * @param parentKey 进入后的父方案路由键
     * @param body      要执行的逻辑
     * @param <T>       返回值类型
     * @return body 的返回值
     */
    public static <T> T callAt(int depth, String parentKey, Supplier<T> body) {
        Deque<String> previous = new ArrayDeque<>(STACK.get());
        Deque<String> scoped = new ArrayDeque<>();
        // 只读栈顶，故除栈顶外的层位只需要占位；深度必须与父线程一致，viewOf 才对得上 id
        for (int level = 1; level < Math.max(0, depth); level++) {
            scoped.push("");
        }
        if (depth > 0) {
            scoped.push(parentKey == null ? "" : parentKey.trim());
        }
        STACK.set(scoped);
        try {
            return body.get();
        } finally {
            if (previous.isEmpty()) {
                STACK.remove();
            } else {
                STACK.set(previous);
            }
        }
    }
}

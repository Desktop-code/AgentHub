package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.ScratchBoard;

import java.util.function.Supplier;

/**
 * 本轮编排共享草稿区的线程上下文。所属域：runtime。
 *
 * <p>为什么用 ThreadLocal 而不是把它塞进每个方法签名：
 * 草稿板是「本轮 run 级」的横切依赖，真正需要它的只有两处——
 * 子派发时决定是否注入 scratch 工具（{@code RouterTrialExecutor}），
 * 以及装配 Toolkit 时把板交给工具实现（{@code ReActTrialExecutor}）。
 * 参数一路透传会污染 6 个以上与草稿无关的方法签名。
 *
 * <p>线程纪律：
 * <ul>
 *   <li>根执行线程在 run 开始 {@link #callAs} 绑定，结束（成功/失败/取消）自动清理，
 *       绝不让 Tomcat / 线程池复用线程时把上一轮的板带进下一轮；</li>
 *   <li>并行子跑在 {@code routerTaskExecutor} 上，提交线程必须用
 *       {@link #callAs} 把同一块板带进 worker，否则 worker 读不到（ThreadLocal 不跨线程继承）；</li>
 *   <li>串行子与内联子直接在根线程执行，天然可见。</li>
 * </ul>
 *
 * <p>未选择 {@code SHARED_SCRATCH} 时板上上下文恒为空，因此
 * 「非该策略不注入 scratch 工具」是结构化保证，不靠额外分支判断。
 */
public final class ScratchContext {

    /** 当前线程看到的板 + 本次写入归属（route_key 或子名） */
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ScratchContext() {
    }

    /**
     * 在指定板上执行一段逻辑，并把该板（含写入归属）绑到当前线程。
     *
     * @param board         草稿板；null 时直接执行，不绑定
     * @param ownerRouteKey 写入归属（route_key / 子名），可空
     * @param body          业务体
     * @param <T>           返回类型
     * @return body 的结果
     */
    public static <T> T callAs(ScratchBoard board, String ownerRouteKey, Supplier<T> body) {
        Scope previous = CURRENT.get();
        if (board == null) {
            return body.get();
        }
        CURRENT.set(new Scope(board, ownerRouteKey == null ? "" : ownerRouteKey));
        try {
            return body.get();
        } finally {
            // 恢复外层作用域而不是无脑 remove：串行子可能嵌套在根作用域内
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * @return 当前线程的草稿板；未选择 SHARED_SCRATCH 或不在编排内时为 null
     */
    public static ScratchBoard board() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.board();
    }

    /**
     * @return 当前线程的作用域（板 + 写入归属）；不在编排内时为 null
     */
    public static Scope current() {
        return CURRENT.get();
    }

    /**
     * 只切换当前写入归属，板保持不变；没有绑板时是空操作。
     *
     * <p>子派发进入 {@code runOneChild} 时调用：同一块板由多个子共享，
     * 但每次 put 的 owner 要写清「是哪个子先写的」。
     *
     * @param ownerRouteKey route_key 或子名
     */
    public static void bindOwner(String ownerRouteKey) {
        Scope scope = CURRENT.get();
        if (scope == null) {
            return;
        }
        CURRENT.set(new Scope(scope.board(), ownerRouteKey == null ? "" : ownerRouteKey));
    }

    /**
     * 清理当前线程绑定；供异常兜底与测试使用。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 线程内作用域。
     *
     * @param board         本轮草稿板
     * @param ownerRouteKey 写入归属
     */
    public record Scope(ScratchBoard board, String ownerRouteKey) {
    }
}

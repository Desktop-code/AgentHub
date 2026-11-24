package com.agenthub.runtime.domain.service;

/**
 * 「这一层派发是不是只派了一位」的线程内标记。所属域：runtime。
 *
 * <p>为什么需要它：嵌套方案（入口判路由分到的那一层）允许成员中途询问，但停等快照只记
 * 「被停住的方案里跑到哪一位」，不记外层这一层还排着谁。若外层同一轮派了两位以上成员，
 * 其中一位停住后，续跑只会把被停住的那条链跑完，外层其余成员整段丢失。
 * 因此嵌套停等只在<b>单链</b>上开放：每一层从根到目标方案都只派了一位。
 *
 * <p>写入方：{@code ChildRunner} 在派发子之前按本层 {@code intents.size()} 写入，退出即还原；
 * 读取方：编排层在决定是否建停等边界时读一次。
 *
 * <p>线程边界：{@link ThreadLocal}，只在「本层派发」这一次同步调用内有效。并行子跑在池线程上，
 * 读不到父线程的值——那恰好是想要的：并行链本来就不允许停等，退化成「不建边界」而不是误开。
 */
public final class SoleChainHolder {

    /** 当前层是否只派了一位；没有显式写入时按 false（保守：不开放停等） */
    private static final ThreadLocal<Boolean> CURRENT = new ThreadLocal<>();

    private SoleChainHolder() {
    }

    /**
     * 标记本次派发是不是单链。
     *
     * @param sole true 表示本层只派了一位，下一层可以建停等边界
     */
    public static void set(boolean sole) {
        if (sole) {
            CURRENT.set(Boolean.TRUE);
        } else {
            CURRENT.remove();
        }
    }

    /**
     * @return true 表示本层只派了一位
     */
    public static boolean allows() {
        return Boolean.TRUE.equals(CURRENT.get());
    }

    /**
     * 恢复到进入本层之前的标记。
     *
     * <p>必须用「调用前读到的值」还原，不能用 clear：嵌套层退出时外层可能仍是单链，
     * 直接清掉会让外层后续的兄弟步骤误判成多链。
     *
     * @param previous 进入本层前的值（{@link #allows()} 的返回值）
     */
    public static void restore(boolean previous) {
        set(previous);
    }

    /**
     * 请求结束时清掉：池线程复用时不能把上一轮的标记带进下一轮。
     */
    public static void clear() {
        CURRENT.remove();
    }
}

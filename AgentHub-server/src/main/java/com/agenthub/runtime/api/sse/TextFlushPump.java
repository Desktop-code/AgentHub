package com.agenthub.runtime.api.sse;

/**
 * 正文合批的「到点即发」泵。所属域：runtime（出站层）。
 *
 * <p>为什么需要它：{@link SseTextBatcher} 的时间窗是「到达驱动」的——只有下一段正文到达时
 * 才会检查时间窗。模型一停顿（思考转正文、准备工具调用、句间停顿时），已攒的半句话
 * 就要等下一段才出去，最长可拖到数百毫秒甚至数秒。加一个定时泵，窗口到点就把缓冲发出去，
 * 尾包不再被「下一段」扣住。
 *
 * <p>线程模型：泵在<b>另一个线程</b>上执行任务，但任务体只调用
 * {@link UnifiedSseWriter} 的同步方法，与请求线程的写出共用同一把锁，
 * 因此帧不会交叉、顺序不乱。实现方必须保证任务异常不外抛（否则调度器会静默停摆）。
 */
interface TextFlushPump {

    /**
     * 登记一次「窗口到点」的 flush。同一段缓冲只会登记一次，重复登记由调用方去重。
     *
     * @param delayMillis 延迟毫秒数，即合批时间窗
     * @param task        到点要执行的任务（已在写入器的锁内）
     */
    void schedule(long delayMillis, Runnable task);

    /**
     * 生产用：进程级共享的守护线程调度泵。
     *
     * @return 共享实例
     */
    static TextFlushPump shared() {
        return SharedTextFlushPump.instance();
    }

    /**
     * 单测用：只把任务记在队列里，由用例显式 {@link Manual#fire()} 触发。
     *
     * <p>为什么要手动：真实定时器会在断言之间抢跑，让「窗内必须只有一帧」这类用例变成偶发失败。
     */
    final class Manual implements TextFlushPump {

        /** 尚未触发的任务（按登记顺序） */
        private final java.util.List<Runnable> tasks = new java.util.ArrayList<>();

        @Override
        public void schedule(long delayMillis, Runnable task) {
            tasks.add(task);
        }

        /**
         * 触发全部已登记任务，模拟「时间窗已到」。
         *
         * @return 本次触发的任务数
         */
        int fire() {
            java.util.List<Runnable> pending = new java.util.ArrayList<>(tasks);
            tasks.clear();
            for (Runnable task : pending) {
                task.run();
            }
            return pending.size();
        }

        /**
         * @return 还有多少任务没触发
         */
        int pending() {
            return tasks.size();
        }
    }
}

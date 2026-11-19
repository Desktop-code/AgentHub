package com.agenthub.runtime.application;

import com.agenthub.runtime.infra.agentscope.builtin.BrowserSessionStore;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一轮运行的手动中断。所属域：runtime。
 * <p>
 * 执行线程在进入模型或工具循环前登记。用户点停止时只打断这一轮：
 * 不再发起下一次模型调用和工具调用，并按现有关闭方式收掉该会话的 browserUse。
 * 不把会话标成已取消，下一轮仍可发送。与撞限是两条终态。
 */
@Component
public class TurnInterrupt {

    private final BrowserSessionStore browsers;
    private final ConcurrentHashMap<Long, Control> controls = new ConcurrentHashMap<>();

    /**
     * @param browsers 按运行会话收掉还开着的浏览器；没有会话时关闭次数为 0
     */
    public TurnInterrupt(BrowserSessionStore browsers) {
        this.browsers = browsers;
    }

    /**
     * 登记当前线程为这一轮的执行线程。同一线程重复进入只加深度，避免内层结束把外层登记清掉。
     *
     * @param sessionId 运行会话
     */
    public void bind(long sessionId) {
        if (sessionId <= 0) {
            return;
        }
        Control existing = controls.get(sessionId);
        if (existing != null && existing.thread == Thread.currentThread()) {
            existing.depth++;
            return;
        }
        controls.put(sessionId, new Control(Thread.currentThread()));
    }

    /**
     * 本轮执行线程离开。深度归零才摘掉登记，进行中的停止标记随登记一起结束。
     *
     * @param sessionId 运行会话
     */
    public void clear(long sessionId) {
        Control control = controls.get(sessionId);
        if (control == null || control.thread != Thread.currentThread()) {
            return;
        }
        if (control.depth > 1) {
            control.depth--;
            return;
        }
        controls.remove(sessionId, control);
    }

    /**
     * @param sessionId 运行会话
     * @return 用户已经要求停掉这一轮
     */
    public boolean isCancelled(long sessionId) {
        Control control = controls.get(sessionId);
        return control != null && control.cancelled.get();
    }

    /**
     * 当前线程是否正执行一轮已被停止的运行。无会话号的单轮调用用这个判断。
     *
     * @return 当前线程名下的运行已被停止
     */
    public boolean isCancelledCurrentThread() {
        Thread current = Thread.currentThread();
        for (Control control : controls.values()) {
            if (control.thread == current && control.cancelled.get()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 停掉正在执行的一轮。没有登记（这一轮没在跑）时返回 false，调用方保持原有「请等待结束」拒绝。
     * 先标记，再关浏览器，最后打断执行线程，让卡在模型或工具上的等待返回。
     *
     * @param sessionId 运行会话
     * @return 确实有一轮在跑并已记下中断
     */
    public boolean cancel(long sessionId) {
        Control control = controls.get(sessionId);
        if (control == null) {
            return false;
        }
        control.cancelled.set(true);
        browsers.closeTrial(sessionId);
        control.thread.interrupt();
        return true;
    }

    /** 一轮执行的登记。depth 只由执行线程自己加减。 */
    private static final class Control {
        private final Thread thread;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private int depth = 1;

        private Control(Thread thread) {
            this.thread = thread;
        }
    }
}

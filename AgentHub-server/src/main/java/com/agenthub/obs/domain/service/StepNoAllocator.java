package com.agenthub.obs.domain.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * obs_span.step_no 发号。所属域：obs。
 * 串行：全局整数 1,2,3…；仅并行扇出：先占一个整数 N 再发 N.1、N.2…；
 * 已在点号支下再挂 .k（如 3.1.1）。同父计数并行安全。
 */
public final class StepNoAllocator {

    private static final long ROOT_KEY = 0L;

    private final AtomicInteger serial = new AtomicInteger(0);
    /** 同父下兄弟序（1-based），并行扇出与点号支共用 */
    private final ConcurrentHashMap<Long, AtomicInteger> siblingOrd = new ConcurrentHashMap<>();
    /** 并行扇出时该父对应的整数基号 N（N.1 / N.2） */
    private final ConcurrentHashMap<Long, Integer> forkBase = new ConcurrentHashMap<>();

    /**
     * 全新 trace 用：串行号从 1 开始。
     */
    public StepNoAllocator() {
        this(0);
    }

    /**
     * 续跑已有 trace 用：串行号从库里最大号接着发，避免同一条 trace 里 step_no 重号。
     *
     * @param initialSerial 已用过的最大串行号；小于 0 按 0 处理
     */
    public StepNoAllocator(int initialSerial) {
        this.serial.set(Math.max(initialSerial, 0));
    }

    /**
     * 为新步骤分配展示号。
     *
     * @param parentId       父行 id；根为空
     * @param parentStepNo   父步 step_no；根或未知可空
     * @param parallelFanout 同父并行扇出（路由多 AGENT、同轮多 TOOL 等）
     * @return 非空 step_no
     */
    public String next(Long parentId, String parentStepNo, boolean parallelFanout) {
        if (parallelFanout) {
            long key = parentKey(parentId);
            int ord = siblingOrd.computeIfAbsent(key, ignored -> new AtomicInteger(0)).incrementAndGet();
            // computeIfAbsent 保证同父只占一次基号，避免两子抢同一 N.1
            int base = forkBase.computeIfAbsent(key, ignored -> serial.incrementAndGet());
            return base + "." + ord;
        }
        // 已在分叉支下：串行也带点号（3.1 → 3.1.1）
        if (parentStepNo != null && parentStepNo.indexOf('.') >= 0) {
            long key = parentKey(parentId);
            int ord = siblingOrd.computeIfAbsent(key, ignored -> new AtomicInteger(0)).incrementAndGet();
            return parentStepNo + "." + ord;
        }
        return String.valueOf(serial.incrementAndGet());
    }

    private long parentKey(Long parentId) {
        return parentId == null ? ROOT_KEY : parentId;
    }
}

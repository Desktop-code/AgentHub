package com.agenthub.eval.domain.model;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * 评测运行状态。所属域：eval 的 domain。落库为 eval_run.status（VARCHAR(32)）。
 * <p>状态机（定稿 §3.2，本域 {@code EvalRunStateMachine} 唯一实现）：
 * <pre>
 *   PENDING   → RUNNING | CANCELING | FAILED
 *   RUNNING   → SUCCEEDED | FAILED | PARTIAL | CANCELING
 *   CANCELING → FAILED | PARTIAL
 *   SUCCEEDED / FAILED / PARTIAL = 终态，禁止再迁移
 * </pre>
 * 线程：枚举无状态。
 */
public enum EvalRunStatus {

    /** 已建跑但尚未开始（本刀生产 create 成功后停在此态，只有刀2b 的 Runner 才会推进） */
    PENDING("待运行"),
    /** 正在逐个 case 执行 */
    RUNNING("运行中"),
    /** 用户已请求取消，等待收尾（级联取消试跑会话 / 收敛未完成 case） */
    CANCELING("取消中"),
    /** 全部 case 跑完且规则门禁通过 */
    SUCCEEDED("成功"),
    /** 基础设施失败，或取消时没有任何 PASS/FAIL 记录 */
    FAILED("失败"),
    /** 门禁已有部分结果，但仍有 case 未完成（基础设施错误或取消后剩余 SKIPPED） */
    PARTIAL("部分完成");

    /** 中文标签，给日志与错误文案用；不参与存储 */
    private final String label;

    EvalRunStatus(String label) {
        this.label = label;
    }

    /**
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }

    /**
     * 是否终态：终态不允许再迁移，也不允许再取消。
     *
     * @return true 表示 SUCCEEDED / FAILED / PARTIAL
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == PARTIAL;
    }

    /**
     * 库内字符串 → 枚举。用于仓储回读，避免把脏数据静默当成 PENDING。
     *
     * @param raw 库内值，可空
     * @return 枚举；空值返回 null
     * @throws BizException 非空但不是合法状态值（库内数据异常，按系统错误处理）
     */
    public static EvalRunStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EvalRunStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.INTERNAL, "评测运行状态非法：" + raw);
        }
    }
}

package com.agenthub.eval.domain.model;

/**
 * EvalScope 桥状态。所属域：eval 的 domain。
 * <p>钉死四值，与 OpenJudge 旁路口径对齐：{@link #DISABLED} / {@link #SKIPPED} / {@link #OK} / {@link #ERROR}。
 * <b>禁止</b>用本状态翻转 {@code gate_passed} 或规则 {@code verdict}。
 */
public enum EvalScopeStatus {

    /** 全局或本 run 未启用，未落盘、未外呼 */
    DISABLED("未启用"),
    /** 启用但缺 base_url（仅触发场景）/ 目录策略跳过 */
    SKIPPED("跳过"),
    /** 导出或触发成功 */
    OK("成功"),
    /** 超时 / 5xx / IO 失败 */
    ERROR("错误");

    private final String label;

    EvalScopeStatus(String label) {
        this.label = label;
    }

    /**
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }
}

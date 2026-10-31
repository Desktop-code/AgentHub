package com.agenthub.runtime.domain.model;

/**
 * 计划中的一步。所属域：runtime。
 * 只描述「做什么」，不含执行结果；结果落在该步的 STEP 事件与 trace 里。
 *
 * @param seq    步骤序号，从 1 开始，执行时严格按它升序串行
 * @param title  一句话标题，写入时间线给人看
 * @param detail 可选补充说明，交给执行模型理解本步要求；为空表示只看标题
 */
public record PlanStep(int seq, String title, String detail) {

    public PlanStep {
        title = title == null ? "" : title.trim();
        detail = detail == null ? "" : detail.trim();
    }

    /**
     * @return 时间线展示用的一行文案，例如「第 1 步 · 查询北京天气」
     */
    public String displayLine() {
        return "第 " + seq + " 步 · " + (title.isEmpty() ? "未命名步骤" : title);
    }
}

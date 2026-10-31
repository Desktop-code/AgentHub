package com.agenthub.runtime.domain.model;

/**
 * 计划中单步的进度快照。所属域：runtime。
 * 同时用于 SSE step 帧与 STEP 事件正文（JSON），两边字段一致，前端只需一套解析。
 *
 * @param seq   步骤序号，对应 {@link PlanStep#seq()}
 * @param title 步骤标题，与计划里的一致
 * @param state 该步当前状态
 * @param text  该步产出的正文；开始与重试时为空，完成/失败时给出结果或失败原因
 */
public record StepView(int seq, String title, StepState state, String text) {

    public StepView {
        title = title == null ? "" : title;
        text = text == null ? "" : text;
    }

    /**
     * @param state 新状态
     * @param text  该状态的正文
     * @return 同一 seq/title 的新快照
     */
    public StepView with(StepState state, String text) {
        return new StepView(seq, title, state, text);
    }

    /**
     * @return 时间线一行的展示文案，例如「第 1 步 · 查询天气 · 完成」
     */
    public String displayLine() {
        String suffix = switch (state) {
            case RUNNING -> "执行中";
            case RETRYING -> "重试中";
            case DONE -> "完成";
            case FAILED -> "失败";
        };
        return "第 " + seq + " 步 · " + title + " · " + suffix;
    }
}

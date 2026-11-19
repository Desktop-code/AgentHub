package com.agenthub.runtime.domain.model;

import java.util.List;

/**
 * 入口预处理步骤的展示快照。所属域：runtime。
 *
 * <p>直播 SSE、内存快照和落库回放共用这一份形状，前端只按它画「先识别、再回传」，
 * 不另拼一套。没有附件时整步不出现。
 *
 * @param status  running / done / failed
 * @param tasks   本轮实际要做的事：vision / file / speech，按执行顺序
 * @param hint    进行中短句，如「正在识图」
 * @param summary 完成后的卡片摘要（可截断）
 * @param text    回显给过程看的识别正文；过长时已带截断说明
 */
public record PreprocessView(String status, List<String> tasks, String hint, String summary, String text) {

    /** 进行中：卡片转圈，过程里写 hint */
    public static final String RUNNING = "running";
    /** 识别完成，即将进入意图分流 */
    public static final String DONE = "done";
    /** 识别失败或入口未绑对应模型，停止分流 */
    public static final String FAILED = "failed";

    /** 图片 / 扫描页走入口 VISION */
    public static final String TASK_VISION = "vision";
    /** 可抽字文档走本地解析 */
    public static final String TASK_FILE = "file";
    /** 录音走入口 ASR */
    public static final String TASK_SPEECH = "speech";

    /**
     * 归一化空值，避免前端对 null 列表再判一次。
     */
    public PreprocessView {
        status = status == null || status.isBlank() ? RUNNING : status.trim();
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        hint = hint == null ? "" : hint;
        summary = summary == null ? "" : summary;
        text = text == null ? "" : text;
    }

    /**
     * 进行中快照。
     *
     * @param tasks 本轮任务
     * @param hint  当前动作
     * @return 展示快照
     */
    public static PreprocessView running(List<String> tasks, String hint) {
        return new PreprocessView(RUNNING, tasks, hint, "", "");
    }

    /**
     * 完成快照。
     *
     * @param tasks   本轮任务
     * @param summary 卡片摘要
     * @param text    识别正文
     * @return 展示快照
     */
    public static PreprocessView done(List<String> tasks, String summary, String text) {
        return new PreprocessView(DONE, tasks, "", summary, text);
    }

    /**
     * 失败快照。摘要和正文都用同一句失败说明，过程与终态一致。
     *
     * @param tasks  本轮任务
     * @param reason 失败原因
     * @return 展示快照
     */
    public static PreprocessView failed(List<String> tasks, String reason) {
        String body = reason == null ? "" : reason.trim();
        return new PreprocessView(FAILED, tasks, "", body, body);
    }
}

package com.agenthub.runtime.domain.model;

/**
 * 计划单步状态。所属域：runtime。写进 STEP 事件正文的 JSON，也出现在 SSE step 帧。
 */
public enum StepState {

    /** 正在执行（含重试中的最后一次尝试） */
    RUNNING,
    /** 本次尝试失败，将按 max_step_retries 再试 */
    RETRYING,
    /** 该步完成且拿到正文 */
    DONE,
    /** 重试次数用尽仍失败，整轮随之失败 */
    FAILED
}

package com.agenthub.memory.domain.model;

/**
 * 会话级 LONG 批抽终态。所属域：memory。
 * DONE / FAILED / SKIPPED 均视为「已处理」，CloseJob / onSessionEnd 再见直接 skip，禁止重试出站。
 */
public enum LongExtractMark {

    /** 已出站并完成（含模型返回空数组） */
    DONE,

    /** 出站失败终态：Key 无效、BizException、超时等，禁止固定间隔重炸 */
    FAILED,

    /** 未出站跳过：开关关、正文过短、无可用模型等 */
    SKIPPED
}

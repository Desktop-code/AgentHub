package com.agenthub.memory.domain.model;

/**
 * 会话级 LONG 批抽终态。所属域：memory。
 * DONE / FAILED / SKIPPED 均视为「这一次空闲已处理」，同一次空闲期间 CloseJob / onSessionEnd 再见直接 skip；
 * 用户在该会话再次发言会清掉标记，下一次空闲按新一轮再抽（不扩枚举、不新增状态）。
 */
public enum LongExtractMark {

    /** 已出站并完成（含模型返回空数组） */
    DONE,

    /**
     * 出站失败终态：Key 无效、BizException、超时等，本次空闲内不再重炸；
     * 用户再次发言清标记后，下一次空闲允许再试一次。
     */
    FAILED,

    /** 未出站跳过：开关关、正文过短、无可用模型等；同样只挡本次空闲 */
    SKIPPED
}

package com.agenthub.runtime.domain.model;

import lombok.Data;

/**
 * 一轮试跑里的中间步。所属域：runtime。
 * 只有引擎给出的思考或工具结果才会出现。思考正文为空时不要建 REASONING。
 */
@Data
public class TrialTrace {

    /** REASONING 或 TOOL */
    private EventType type;
    /** 工具名。推理可空 */
    private String name;
    /** 工具是否成功。推理忽略 */
    private boolean success;
    /** 工具入参。已脱敏，禁止密钥原文；推理为空 */
    private String arguments;
    /** 推理全文或工具结果。调用方写入前再脱敏 */
    private String content;
}

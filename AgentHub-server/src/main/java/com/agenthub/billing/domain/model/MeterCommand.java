package com.agenthub.billing.domain.model;

import lombok.Data;

/**
 * 一次模型调用的计量入参。所属域：billing。
 * appId 一期可空。token 缺省按 0 记，不在这里调用模型。
 */
@Data
public class MeterCommand {

    private Long agentId;
    private Long modelInstanceId;
    /** 一期可空，授权补全前不作为过滤维 */
    private Long appId;
    private Long sessionId;
    private String channel;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private String remark;
    /**
     * 业务唯一键：同一次计费事件只允许一条流水。
     * 由 runtime 按「轮次幂等键 + 本轮调用序号」生成；为空表示该次调用不参与去重
     * （无客户端幂等键、或键在子线程未传播），此时按现网行为直接落账。
     */
    private String dedupeKey;
    /** 写入 created_by；试跑时是平台用户 */
    private Long operatorId;
}

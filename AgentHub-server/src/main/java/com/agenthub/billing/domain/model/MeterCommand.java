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
    /** 写入 created_by；试跑时是平台用户 */
    private Long operatorId;
}

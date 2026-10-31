package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 与模型实例的绑定。所属域：agent。对应 agent_model_bind。
 * purpose 为空时沿用模型实例自己的 capability。
 */
@Data
public class AgentModelBind {

    private Long id;
    private Long agentId;
    private Long modelInstanceId;
    private String purpose;
    /** 非 CHAT 用途引用的 L2 模板 id；空=按用途取当前生效版 */
    private Long promptTemplateId;
    /** 钉死的 L2 版本号 */
    private Integer templateVersion;
    /** L3 soft 覆盖 JSON */
    private String promptOverrideJson;
    /** L3 追加区，可空 */
    private String promptExtra;
    private Integer priority;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

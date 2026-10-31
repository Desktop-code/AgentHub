package com.agenthub.rag.api.dto;

import lombok.Data;

/**
 * Agent 检索链路重排配置出参。所属域：rag。
 * 数据来自 agent_model_bind(purpose=RERANK) 的唯一一条启用行；未配置时字段为空。
 */
@Data
public class AgentRerankResp {

    /** 绑定 id；未配置为 null */
    private Long bindId;
    /** 重排模型实例 id；未配置为 null */
    private Long modelInstanceId;
    /** 实例展示名，便于前端直接回显；实例已删时为 null */
    private String displayName;
    /** 模型编码，便于前端二次确认 */
    private String modelId;
}

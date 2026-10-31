package com.agenthub.rag.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Agent 检索链路重排配置入参。所属域：rag。
 * modelInstanceId 传空或 0 表示清空重排（不再重排）；其它值必须是已启用、用途为 RERANK 的实例。
 */
@Data
public class AgentRerankUpdateReq {

    /** 重排模型实例；0 或空 = 关闭重排 */
    @Min(value = 0, message = "不能小于 0")
    @Max(value = Integer.MAX_VALUE, message = "超出范围")
    private Long modelInstanceId;
}

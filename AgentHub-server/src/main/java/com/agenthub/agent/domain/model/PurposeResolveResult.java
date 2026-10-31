package com.agenthub.agent.domain.model;

import lombok.Builder;
import lombok.Value;

/**
 * 用途槽解析结果。所属域：agent。
 * failover_priority 表示本槽第几备（1..3）；degraded_purpose 非空表示跨槽降到 CHAT。
 */
@Value
@Builder
public class PurposeResolveResult {

    /** 命中的模型实例 id */
    long instanceId;
    /** 请求的用途 */
    String requestedPurpose;
    /**
     * 来源：SLOT / PLATFORM / ENV / DEGRADED_CHAT
     */
    String source;
    /** 槽内备援序号；非槽内命中时为 null */
    Integer failoverPriority;
    /** 跨槽降级时为 CHAT；否则 null */
    String degradedPurpose;

    /**
     * @return 是否槽内 failover
     */
    public boolean slotFailover() {
        return "SLOT".equals(source) && failoverPriority != null && failoverPriority > 1;
    }

    /**
     * @return 是否降级自 CHAT
     */
    public boolean degradedFromChat() {
        return "DEGRADED_CHAT".equals(source) || "CHAT".equals(degradedPurpose);
    }
}

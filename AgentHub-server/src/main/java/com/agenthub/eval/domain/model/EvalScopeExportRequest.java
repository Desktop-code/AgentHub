package com.agenthub.eval.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * EvalScope 桥导出/触发入参。所属域：eval 的 domain。
 * <p>由应用层组装；Adapter 只消费路径、清单 JSON 与可选 HTTP 字段。
 */
@Data
public class EvalScopeExportRequest {

    /** 跑批 id（幂等覆盖路径用） */
    private Long runId;
    /** 租户（Adapter 不校验，由 AppService 保证） */
    private Long tenantId;
    /** 导出根目录（已解析绝对/相对 Path 字符串） */
    private String exportDir;
    /** 已脱敏的清单 JSON */
    private JsonNode manifest;
    /** 是否在落盘后尝试 HTTP 触发 */
    private boolean trigger;
    /** 外部基址；空则 trigger → SKIPPED */
    private String baseUrl;
    /** HTTP 超时毫秒 */
    private long timeoutMs = 60_000L;
    /** 公开基准名（如 MMLU / GSM8K）；可空 */
    private String benchmark;
    /** 备注 */
    private String note;
    /** 透传 client_token（建议 runId） */
    private String clientToken;
}

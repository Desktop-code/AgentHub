package com.agenthub.tool.api.dto;

import lombok.Data;

/**
 * MCP 链接探测结果。所属域：tool。
 * 失败也走 HTTP 200，用 success 区分；message 已脱敏，不含鉴权头。
 */
@Data
public class SourceProbeResp {

    private boolean success;
    private long latencyMs;
    /** 探测成功时远端 tools/list 条数；失败为 0 */
    private int toolCount;
    private String message;

    /**
     * @param latencyMs 耗时
     * @param toolCount 拉到的工具数
     * @param message   脱敏摘要
     * @return 成功结果
     */
    public static SourceProbeResp ok(long latencyMs, int toolCount, String message) {
        SourceProbeResp resp = new SourceProbeResp();
        resp.success = true;
        resp.latencyMs = latencyMs;
        resp.toolCount = toolCount;
        resp.message = message;
        return resp;
    }

    /**
     * @param latencyMs 耗时
     * @param message   脱敏失败说明
     * @return 失败结果
     */
    public static SourceProbeResp fail(long latencyMs, String message) {
        SourceProbeResp resp = new SourceProbeResp();
        resp.success = false;
        resp.latencyMs = latencyMs;
        resp.toolCount = 0;
        resp.message = message;
        return resp;
    }
}

package com.agenthub.tool.api.dto;

import lombok.Data;

/**
 * MCP 同步结果。所属域：tool。失败也走 HTTP 200，用 success 区分，message 已脱敏。
 * retired 表示本次按远端清单对齐时下架（逻辑删除）的本地项条数，含远端已删项与同源同名重复行。
 */
@Data
public class SyncResp {

    private boolean success;
    private long latencyMs;
    private int upserted;
    private int created;
    private int updated;
    private int retired;
    private String message;

    /**
     * @param latencyMs 耗时
     * @param created   新发现并停用的条数
     * @param updated   保留原启停、只更新资料的条数
     * @param retired   本次对齐下架的条数（远端已删 + 同源同名重复行）
     * @param message   脱敏摘要
     * @return 成功结果
     */
    public static SyncResp ok(long latencyMs, int created, int updated, int retired, String message) {
        SyncResp resp = new SyncResp();
        resp.success = true;
        resp.latencyMs = latencyMs;
        resp.created = created;
        resp.updated = updated;
        resp.retired = retired;
        resp.upserted = created + updated;
        resp.message = message;
        return resp;
    }

    /**
     * @param latencyMs 耗时
     * @param message   脱敏后的失败说明
     * @return 失败结果，写入计数为 0
     */
    public static SyncResp fail(long latencyMs, String message) {
        SyncResp resp = new SyncResp();
        resp.success = false;
        resp.latencyMs = latencyMs;
        resp.message = message;
        return resp;
    }
}

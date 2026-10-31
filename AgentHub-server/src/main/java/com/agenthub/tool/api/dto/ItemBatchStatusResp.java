package com.agenthub.tool.api.dto;

import lombok.Data;

/**
 * 工具项批量启停结果。所属域：tool。
 * updated 为本次实际被改写的行数（已是目标状态的行也被计入，便于前端直接提示「已处理 N 项」）。
 */
@Data
public class ItemBatchStatusResp {

    private Long sourceId;
    private int status;
    private int updated;

    /**
     * @param sourceId 工具源 id
     * @param status   目标状态，1 启用 / 0 停用
     * @param updated  实际更新行数
     * @return 结果
     */
    public static ItemBatchStatusResp of(Long sourceId, int status, int updated) {
        ItemBatchStatusResp resp = new ItemBatchStatusResp();
        resp.sourceId = sourceId;
        resp.status = status;
        resp.updated = updated;
        return resp;
    }
}

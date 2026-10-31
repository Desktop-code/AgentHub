package com.agenthub.tool.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工具项分页条件。所属域：tool。sourceId、type、status、keyword 都可空。
 * 全站列表默认每页 10 条；前端工具管理右栏与之一致。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ItemPageReq extends PageQuery {

    private Long sourceId;
    private String type;
    private Integer status;
    private String keyword;

    /**
     * 未显式传 size 时按该源工具表默认 10 条（与全站列表一致），显式传值仍以入参为准。
     */
    public ItemPageReq() {
        setSize(10);
    }
}

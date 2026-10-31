package com.agenthub.tool.domain.model;

import lombok.Data;

import java.util.List;

/**
 * 工具项分页条件。所属域：tool。空白筛选项表示不限制。
 * sourceIds 由类型筛选预先解析；空列表表示类型下没有任何源。
 * 默认每页 10 条，与全站列表默认一致。
 */
@Data
public class ItemQuery {

    private long current = 1;
    private long size = 10;
    private Long sourceId;
    private Integer status;
    private String keyword;
    private List<Long> sourceIds;
}

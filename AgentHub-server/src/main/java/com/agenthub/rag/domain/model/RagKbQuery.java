package com.agenthub.rag.domain.model;

import lombok.Data;

/**
 * 知识库分页筛选条件。所属域：rag。所有字段可空。
 */
@Data
public class RagKbQuery {

    /** 页码，从 1 起 */
    private long current = 1;
    /** 每页条数，上限由仓储裁剪到 100 */
    private long size = 10;
    /** 名称 / 描述模糊 */
    private String keyword;
    /** READY / INIT_FAILED / DISABLED */
    private String status;
    /** 可见性筛选 */
    private String visibility;
}

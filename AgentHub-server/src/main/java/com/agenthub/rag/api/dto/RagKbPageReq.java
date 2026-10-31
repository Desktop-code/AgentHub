package com.agenthub.rag.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库分页请求。所属域：rag。keyword / status / visibility 均可空。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RagKbPageReq extends PageQuery {

    /** 名称或描述模糊 */
    private String keyword;
    /** READY / INIT_FAILED / DISABLED */
    private String status;
    /** PUBLIC / PRIVATE / RESTRICTED */
    private String visibility;
}

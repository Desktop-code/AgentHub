package com.agenthub.rag.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档分页请求。所属域：rag。按知识库圈定，可按标题关键字模糊过滤。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RagDocumentPageReq extends PageQuery {

    /** 标题关键字，可空；只匹配文档标题，不搜正文 */
    private String keyword;
}

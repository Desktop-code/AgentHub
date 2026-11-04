package com.agenthub.workflow.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流定义分页入参。所属域：workflow。
 * <p>keyword 匹配 code / name；status 为 DRAFT_ONLY / HAS_RELEASE。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkflowDefPageReq extends PageQuery {

    /** 关键字，匹配 code / 中文名，可空 */
    private String keyword;

    /** 生命周期状态，可空 */
    private String status;
}

package com.agenthub.workflow.domain.model;

import lombok.Data;

/**
 * 工作流列表查询条件。所属域：workflow 的 domain。
 * <p>tenantId 由应用层传入（当前租户），仓储不做隐式取值，便于单测替换。
 */
@Data
public class WorkflowDefQuery {

    /** 页码，从 1 开始 */
    private long current = 1;
    /** 每页条数，上限 100 */
    private long size = 10;
    /** 关键字，匹配 code / name，可空 */
    private String keyword;
    /** 生命周期状态，可空 */
    private String status;
    /** 当前租户 */
    private long tenantId;
}

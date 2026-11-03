package com.agenthub.eval.domain.model;

import lombok.Data;

/**
 * 套件列表查询条件。所属域：eval。
 * <p>tenantId 由应用层传入（当前租户），仓储不做隐式取值，便于单测替换。
 */
@Data
public class EvalSuiteQuery {

    /** 页码，从 1 开始 */
    private long current = 1;
    /** 每页条数，上限 100 */
    private long size = 10;
    /** 关键字，匹配 suite_key / name，可空 */
    private String keyword;
    /** 当前租户 */
    private long tenantId;
    /** 是否连同 tenant_id=0 的平台样例一起列出（只读） */
    private boolean includePlatform;
}

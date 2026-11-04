package com.agenthub.eval.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 套件分页入参。所属域：eval。
 * <p>includePlatform=true 时连同 tenant_id=0 的平台样例一起列出（只读）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EvalSuitePageReq extends PageQuery {

    /** 关键字，匹配 suite_key / 名称，可空 */
    private String keyword;

    /** 是否包含平台内置样例，默认包含，便于「克隆到本租户」 */
    private Boolean includePlatform = Boolean.TRUE;
}

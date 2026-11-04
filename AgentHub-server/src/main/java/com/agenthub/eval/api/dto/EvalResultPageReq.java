package com.agenthub.eval.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 跑批结果分页入参。所属域：eval。
 * <p>本刀只按 sort_order 固定排序，不开放筛选，避免过早固化查询口径。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EvalResultPageReq extends PageQuery {
}

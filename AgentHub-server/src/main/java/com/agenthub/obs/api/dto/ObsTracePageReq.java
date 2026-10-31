package com.agenthub.obs.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 中栏 trace 分页。所属域：obs。q 模糊匹配 title。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ObsTracePageReq extends PageQuery {

    private String q;
}

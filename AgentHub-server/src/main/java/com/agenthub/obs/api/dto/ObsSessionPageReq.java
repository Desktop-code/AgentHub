package com.agenthub.obs.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 左栏会话分页。所属域：obs。q 和 agentId 都可空。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ObsSessionPageReq extends PageQuery {

    /** 模糊匹配预览或会话 id */
    private String q;
    /** 空表示全部 Agent */
    private Long agentId;
}

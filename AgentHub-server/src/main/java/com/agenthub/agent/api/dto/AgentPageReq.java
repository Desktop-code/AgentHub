package com.agenthub.agent.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 分页。所属域：agent。keyword、runMode、status 可空。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentPageReq extends PageQuery {

    private String keyword;
    private String runMode;
    private Integer status;
}

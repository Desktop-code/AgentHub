package com.agenthub.agent.domain.model;

import lombok.Data;

/**
 * Agent 列表条件。所属域：agent。keyword、runMode、status 都可空。
 */
@Data
public class AgentQuery {

    private long current = 1;
    private long size = 20;
    private String keyword;
    private String runMode;
    private Integer status;
}

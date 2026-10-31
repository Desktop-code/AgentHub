package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 子路由行。所属域：agent。status 是路由行自己的启停，childStatus 是子 Agent 的启停。
 */
@Data
public class RouteResp {

    private Long id;
    private Long parentAgentId;
    private Long childAgentId;
    private String childName;
    private String childCode;
    private String childRunMode;
    private Integer childStatus;
    private String routeKey;
    private String intent;
    private Integer sortOrder;
    private Integer status;
}

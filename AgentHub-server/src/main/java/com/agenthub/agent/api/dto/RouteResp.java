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
    /**
     * 子 Agent 是否允许被编排调用（0=禁止）。前端校验据此对已存在候选报 error，
     * 子被后来关掉开关时不必等保存失败才发现。
     */
    private Integer childAllowOrchestrated;
    private String routeKey;
    private String intent;
    private Integer sortOrder;
    private Integer status;
}

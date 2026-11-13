package com.agenthub.agent.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 分页。所属域：agent。keyword、runMode、status 可空。
 *
 * <p>scope=SINGLE 表示「只看单兵」：Agent 管理页固定传该值，服务端据此排除
 * ROUTER 运行模式的实体，以及已作为多智能体方案绑根的主控 Agent。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentPageReq extends PageQuery {

    private String keyword;
    private String runMode;
    private Integer status;
    /** 列表口径：SINGLE＝仅单兵（ReAct / 计划执行）；空＝全部（其它下拉仍可拿到 Router） */
    private String scope;
}

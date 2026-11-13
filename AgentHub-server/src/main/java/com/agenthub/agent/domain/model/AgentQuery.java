package com.agenthub.agent.domain.model;

import lombok.Data;

/**
 * Agent 列表条件。所属域：agent。keyword、runMode、status 都可空。
 *
 * <p>singleOnly=true 时只返回单兵（排除 ROUTER 与多智能体方案绑根的主控），
 * 供 Agent 管理页使用；其它业务下拉（评测 / 计费 / 记忆等）保持全量口径。
 */
@Data
public class AgentQuery {

    private long current = 1;
    private long size = 20;
    private String keyword;
    private String runMode;
    private Integer status;
    /** 仅单兵：排除 ROUTER 运行模式与 ma_def 绑根的主控 Agent */
    private boolean singleOnly;
}

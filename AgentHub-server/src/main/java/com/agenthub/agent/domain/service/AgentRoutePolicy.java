package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * 一层 Router 的写入规则。所属域：agent。
 * 只拒绝自引用和子节点不是 REACT 的情况，不做图上的多层环检测。
 */
public final class AgentRoutePolicy {

    private AgentRoutePolicy() {
    }

    /**
     * 新路由或重新启用时调用。父节点必须当前就是 ROUTER。
     *
     * @param parent 未删除的父 Agent
     * @param child  未删除的子 Agent
     * @throws BizException 父节点不是 ROUTER、自己指向自己、子节点不是 REACT 或子节点未启用
     */
    public static void assertCanBind(AgentDef parent, AgentDef child) {
        if (parent == null || child == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        if (!AgentRunMode.ROUTER.name().equals(parent.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有 ROUTER 可以配置子 Agent");
        }
        if (parent.getId() != null && parent.getId().equals(child.getId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能把 Agent 配成自己的子节点");
        }
        if (!AgentRunMode.REACT.name().equals(child.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "子 Agent 必须是 REACT，一期不支持深多层");
        }
        if (child.getStatus() == null || child.getStatus() != AgentStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能绑定已启用的子 Agent");
        }
    }
}

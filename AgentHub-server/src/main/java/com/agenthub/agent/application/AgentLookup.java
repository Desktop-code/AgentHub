package com.agenthub.agent.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 读取未删除的 Agent 定义。所属域：agent。绑定和路由共用，避免各处各写一遍不存在的文案。
 */
@Component
public class AgentLookup {

    private final AgentDefRepository agents;

    /**
     * @param agents 定义仓储
     */
    public AgentLookup(AgentDefRepository agents) {
        this.agents = agents;
    }

    /**
     * @param id 主键
     * @return 未删除的定义
     * @throws BizException 不存在时 NOT_FOUND
     */
    public AgentDef require(Long id) {
        return agents.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在"));
    }

    /**
     * 列表展示用。子 Agent 已删时返回空，不让整页路由失败。
     *
     * @param id 主键，可空
     * @return 未删除的定义
     */
    public Optional<AgentDef> find(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return agents.findById(id);
    }
}

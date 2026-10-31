package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 未删除范围内 code 唯一。所属域：agent。
 * agent_def 只有普通索引，约束放在这里，不改表。
 */
@Component
public class AgentCodePolicy {

    private final AgentDefRepository agents;

    /**
     * @param agents 定义仓储
     */
    public AgentCodePolicy(AgentDefRepository agents) {
        this.agents = agents;
    }

    /**
     * @param code      已经去掉首尾空白的编码
     * @param excludeId 更新时排除当前行；新建为 null
     * @throws BizException 编码已被未删除行占用时 DUPLICATE
     */
    public void assertUnique(String code, Long excludeId) {
        if (agents.existsByCode(code, excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, "Agent 编码已存在");
        }
    }
}

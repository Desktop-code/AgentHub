package com.agenthub.memory.application;

import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.repository.MemoryPolicyRepository;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记忆策略用例。所属域：memory。
 * 每个 Agent 只保留一条有效策略：已有则更新，没有则新增。窗口参数不得超过平台硬顶。
 */
@Service
public class MemoryPolicyAppService {

    private final MemoryPolicyRepository policies;
    private final AgentDefRepository agents;
    private final ModelInstanceRepository instances;
    private final MemoryProperties properties;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param policies        策略仓储
     * @param agents          确认 Agent 还在
     * @param instances       摘要和抽取模型必须是可对话实例
     * @param properties      平台硬顶
     * @param operatorContext 操作人
     * @param operLogWriter   只记 id 和动作
     */
    public MemoryPolicyAppService(MemoryPolicyRepository policies, AgentDefRepository agents,
                                  ModelInstanceRepository instances, MemoryProperties properties,
                                  OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.policies = policies;
        this.agents = agents;
        this.instances = instances;
        this.properties = properties;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 没有入库行时返回平台默认，id 为空，前端保存时再插入。
     *
     * @param agentId Agent 主键
     * @return 有效策略
     * @throws BizException Agent 不存在
     */
    public MemoryPolicy get(Long agentId) {
        requireAgent(agentId);
        return policies.findActiveByAgentId(agentId).orElseGet(() -> properties.defaults(agentId));
    }

    /**
     * @param policy 前端提交的策略，agentId 必填
     * @return 保存后的策略
     * @throws BizException 超过硬顶、模型不可用或 Agent 不存在
     */
    @Transactional
    public MemoryPolicy save(MemoryPolicy policy) {
        requireAgent(policy.getAgentId());
        validate(policy);
        long operatorId = operatorContext.requireUserId();
        MemoryPolicy existing = policies.findActiveByAgentId(policy.getAgentId()).orElse(null);
        if (existing == null) {
            policy.setStatus(1);
            Long id = policies.insert(policy, operatorId);
            operLogWriter.write("memory", "edit", "memory_agent_policy", String.valueOf(id), "保存记忆策略 " + id);
            return policies.findById(id).orElseThrow();
        }
        policy.setId(existing.getId());
        policy.setStatus(1);
        policies.update(policy, operatorId);
        operLogWriter.write("memory", "edit", "memory_agent_policy", String.valueOf(existing.getId()),
                "保存记忆策略 " + existing.getId());
        return policies.findById(existing.getId()).orElseThrow();
    }

    /**
     * 试跑读取。库里没有就用默认，有则压到硬顶。
     *
     * @param agentId Agent
     * @return 永远非空
     */
    public MemoryPolicy effective(Long agentId) {
        return policies.findActiveByAgentId(agentId)
                .map(properties::clamp)
                .orElseGet(() -> properties.defaults(agentId));
    }

    private void requireAgent(Long agentId) {
        if (agentId == null || agents.findById(agentId).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
    }

    private void validate(MemoryPolicy policy) {
        // 短期记忆是多轮对话基础设施，不允许关闭
        policy.setEnableShort(1);
        policy.setEnableLong(flag(policy.getEnableLong()));
        policy.setEnableLongExtract(flag(policy.getEnableLongExtract()));
        policy.setLongExtractOnSessionEnd(flag(policy.getLongExtractOnSessionEnd()));
        policy.setLongExtractOnExplicit(flag(policy.getLongExtractOnExplicit()));
        policy.setMaxShortItems(bounded(policy.getMaxShortItems(), 1, 500, "短期条数"));
        policy.setMaxLongItems(bounded(policy.getMaxLongItems(), 1, 500, "长期条数"));
        policy.setCtxMaxTurns(bounded(policy.getCtxMaxTurns(), 1, properties.getCtxMaxTurnsCap(), "窗口轮数"));
        policy.setCtxMaxChars(bounded(policy.getCtxMaxChars(), 256, properties.getCtxMaxCharsCap(), "窗口字符"));
        // ctx_idle_ttl_sec 同时是窗口 Redis TTL 与「空闲多久后收口批抽」，下限 60 秒；
        // 调度侧不写死 7200，一律读这一列，所以这里挡住的非法值就是批抽到期判定的非法值
        policy.setCtxIdleTtlSec(bounded(policy.getCtxIdleTtlSec(), 60, properties.getCtxIdleTtlMaxSec(),
                "空闲时间(秒)"));
        policy.setShortDefaultTtlSec(bounded(policy.getShortDefaultTtlSec(), 60,
                properties.getShortDefaultTtlMaxSec(), "短期 TTL"));
        assertChat(policy.getSummaryModelInstanceId(), "摘要模型");
        assertChat(policy.getLongExtractModelInstanceId(), "抽取模型");
    }

    private void assertChat(Long instanceId, String label) {
        if (instanceId == null) {
            return;
        }
        ModelInstance instance = instances.findById(instanceId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, label + "不存在"));
        boolean chat = ModelCapability.tryParse(instance.getCapability()).map(ModelCapability::usesChat).orElse(false);
        if (!chat) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "必须是可对话实例");
        }
    }

    private static int flag(Integer raw) {
        if (raw == null) {
            return 0;
        }
        if (raw != 0 && raw != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "开关只能是 0 或 1");
        }
        return raw;
    }

    private static int bounded(Integer raw, int min, int max, String label) {
        if (raw == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        if (raw < min || raw > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "必须在 " + min + " 到 " + max + " 之间");
        }
        return raw;
    }
}

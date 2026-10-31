package com.agenthub.rag.application;

import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.agent.domain.service.PurposeSlotRules;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 读 Agent 侧的重排绑定，供检索链路覆盖知识库自带的重排模型。所属域：rag。
 * <p>
 * 单一真相：Agent 的 Rerank 选择存 {@code agent_model_bind(purpose=RERANK)}，
 * 配置入口只在 Agent 详情「知识库」Tab；本类只读，不写绑定，
 * 也不依赖 agent 的 AppService / Entity（沿用跨域只读仓储的既有做法）。
 * <p>
 * 语义：存在一条启用且实例可用的 RERANK 绑定 = 该 Agent 的检索链路启用重排，
 * 且模型就是它；没有绑定则回落到知识库自身的 {@code rerankModelId} + {@code rerankEnabled}。
 */
@Component
public class AgentRerankLookup {

    private final AgentModelBindRepository binds;
    private final ModelInstanceRepository instances;

    /**
     * @param binds     Agent 模型绑定仓储（只读）
     * @param instances 模型实例仓储（只读，用于确认实例仍启用）
     */
    public AgentRerankLookup(AgentModelBindRepository binds, ModelInstanceRepository instances) {
        this.binds = binds;
        this.instances = instances;
    }

    /**
     * 取该 Agent 生效的重排实例。
     * <p>
     * 绑定已按 priority 升序返回，这里取第一条「绑定启用 + 实例启用 + 用途确实是 RERANK」的行；
     * 实例被停用或已删时视同未配置，交给知识库自身的重排配置兜底。
     *
     * @param agentId Agent 主键，可空
     * @return 重排模型实例 id；未配置时为空
     */
    public Optional<Long> enabledRerankModelId(Long agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        for (AgentModelBind bind : binds.listByAgentId(agentId)) {
            if (bind.getStatus() == null || bind.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            Optional<ModelInstance> instance = instances.findById(bind.getModelInstanceId());
            if (instance.isEmpty() || instance.get().getStatus() == null
                    || instance.get().getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            // 用途以「绑定覆盖优先、否则跟实例 capability」解析，与配置页展示一致
            Optional<ModelCapability> purpose = PurposeSlotRules.effectivePurpose(bind, instance.get());
            if (purpose.isPresent() && purpose.get() == ModelCapability.RERANK) {
                return Optional.of(instance.get().getId());
            }
        }
        return Optional.empty();
    }
}

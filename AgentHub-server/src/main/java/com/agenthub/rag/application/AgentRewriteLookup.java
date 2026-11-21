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
 * 读 Agent 的查询改写模型槽。所属域：rag。
 * <p>
 * 单一真相：改写的模型与 L3 提示词都挂在 {@code agent_model_bind(purpose=REWRITE)} 上，
 * 本类只读不写，也不依赖 agent 的 AppService / Entity（沿用跨域只读仓储的既有做法）。
 * <p>
 * <b>只认本槽，不做任何降级</b>：槽为空 / 绑定停用 / 实例停用时返回空，
 * 由调用方按「不改写、用原问句检索」处理。绝不回落到 CHAT 槽或平台默认模型——
 * 拿主对话模型改写问句会改变检索语义，且用户看不到这层替换。
 */
@Component
public class AgentRewriteLookup {

    private final AgentModelBindRepository binds;
    private final ModelInstanceRepository instances;

    /**
     * @param binds     Agent 模型绑定仓储（只读）
     * @param instances 模型实例仓储（只读，用于确认实例仍启用）
     */
    public AgentRewriteLookup(AgentModelBindRepository binds, ModelInstanceRepository instances) {
        this.binds = binds;
        this.instances = instances;
    }

    /**
     * 取该 Agent 生效的 REWRITE 绑定行（含 L3 提示词四列）。
     * <p>
     * 绑定已按 priority 升序返回，这里取第一条「绑定启用 + 实例启用 + 用途确实是 REWRITE」的行；
     * 实例被停用或已删时视同未配置。
     *
     * @param agentId Agent 主键，可空
     * @return 绑定行；未配置或不可用时为空
     */
    public Optional<AgentModelBind> enabledRewriteBind(Long agentId) {
        if (agentId == null || agentId <= 0) {
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
            if (purpose.isPresent() && purpose.get() == ModelCapability.REWRITE) {
                return Optional.of(bind);
            }
        }
        return Optional.empty();
    }
}

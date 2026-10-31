package com.agenthub.agent.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.PurposeResolveResult;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.agent.domain.service.PurposeSlotRules;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 按用途槽解析模型实例。所属域：agent。
 * 顺序：策略首选 → 本槽 priority 备援 → 平台默认该用途 → 环境默认该用途 →（允许时）降 CHAT 槽。
 * SUMMARY 与 MEMORY 互不借用。
 */
@Component
public class PurposeModelResolver {

    private final AgentModelBindRepository binds;
    private final ModelInstanceRepository instances;

    /**
     * @param binds     Agent 模型绑定
     * @param instances 实例启停与用途
     */
    public PurposeModelResolver(AgentModelBindRepository binds, ModelInstanceRepository instances) {
        this.binds = binds;
        this.instances = instances;
    }

    /**
     * @param agentId          Agent；可空则跳过绑定槽
     * @param purpose          请求用途
     * @param preferredId      策略上的首选实例，可空
     * @param envFallbackId    环境变量兜底，可空
     * @param allowChatDegrade 是否允许再降 CHAT（CHAT 自身应为 false）
     * @return 命中结果；都没有则空
     */
    public Optional<PurposeResolveResult> resolve(Long agentId, ModelCapability purpose, Long preferredId,
                                                  Long envFallbackId, boolean allowChatDegrade) {
        if (purpose == null) {
            return Optional.empty();
        }
        if (preferredId != null && preferredId > 0 && usable(preferredId)) {
            return Optional.of(PurposeResolveResult.builder()
                    .instanceId(preferredId)
                    .requestedPurpose(purpose.name())
                    .source("POLICY")
                    .build());
        }
        Optional<PurposeResolveResult> fromSlot = fromSlot(agentId, purpose);
        if (fromSlot.isPresent()) {
            return fromSlot;
        }
        Optional<Long> platform = platformDefault(purpose);
        if (platform.isPresent()) {
            return Optional.of(PurposeResolveResult.builder()
                    .instanceId(platform.get())
                    .requestedPurpose(purpose.name())
                    .source("PLATFORM")
                    .build());
        }
        if (envFallbackId != null && envFallbackId > 0 && usable(envFallbackId)) {
            return Optional.of(PurposeResolveResult.builder()
                    .instanceId(envFallbackId)
                    .requestedPurpose(purpose.name())
                    .source("ENV")
                    .build());
        }
        // 跨槽降 CHAT：不再二次降级
        if (allowChatDegrade && purpose.allowsChatDegrade() && purpose != ModelCapability.CHAT) {
            Optional<PurposeResolveResult> chat = resolve(agentId, ModelCapability.CHAT, null, null, false);
            if (chat.isPresent()) {
                PurposeResolveResult hit = chat.get();
                return Optional.of(PurposeResolveResult.builder()
                        .instanceId(hit.getInstanceId())
                        .requestedPurpose(purpose.name())
                        .source("DEGRADED_CHAT")
                        .failoverPriority(hit.getFailoverPriority())
                        .degradedPurpose(ModelCapability.CHAT.name())
                        .build());
            }
        }
        return Optional.empty();
    }

    /**
     * 列出某用途槽内启用绑定（已按 priority、id 排序），供 UI 与校验。
     *
     * @param agentId Agent
     * @param purpose 用途
     * @return 有序绑定
     */
    public List<AgentModelBind> listSlotBinds(Long agentId, ModelCapability purpose) {
        if (agentId == null || purpose == null) {
            return List.of();
        }
        List<AgentModelBind> matched = new ArrayList<>();
        for (AgentModelBind bind : binds.listByAgentId(agentId)) {
            Optional<ModelInstance> instance = instances.findById(bind.getModelInstanceId());
            if (instance.isEmpty()) {
                continue;
            }
            Optional<ModelCapability> effective = PurposeSlotRules.effectivePurpose(bind, instance.get());
            if (effective.isPresent() && effective.get() == purpose) {
                matched.add(bind);
            }
        }
        return PurposeSlotRules.sortByPriority(matched);
    }

    private Optional<PurposeResolveResult> fromSlot(Long agentId, ModelCapability purpose) {
        List<AgentModelBind> slot = listSlotBinds(agentId, purpose);
        int rank = 0;
        for (AgentModelBind bind : slot) {
            rank++;
            if (bind.getStatus() == null || bind.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            if (usable(bind.getModelInstanceId())) {
                return Optional.of(PurposeResolveResult.builder()
                        .instanceId(bind.getModelInstanceId())
                        .requestedPurpose(purpose.name())
                        .source("SLOT")
                        .failoverPriority(rank)
                        .build());
            }
        }
        return Optional.empty();
    }

    private Optional<Long> platformDefault(ModelCapability purpose) {
        for (String code : ModelCapability.storageCodes(purpose)) {
            for (ModelInstance instance : instances.listEnabledByCapability(code)) {
                if (usable(instance.getId())) {
                    return Optional.of(instance.getId());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 实例可用：存在、启用、且为对话类探活形态（嵌入等槽由调用方自行约束；本解析器用于文本用途）。
     */
    boolean usable(long instanceId) {
        ModelInstance instance = instances.findById(instanceId).orElse(null);
        if (instance == null || !RecordStatus.isEnabled(instance.getStatus()) || instance.getCapability() == null) {
            return false;
        }
        return ModelCapability.tryParse(instance.getCapability()).map(ModelCapability::usesChat).orElse(false);
    }
}

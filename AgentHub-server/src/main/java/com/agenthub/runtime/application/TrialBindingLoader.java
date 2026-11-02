package com.agenthub.runtime.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.application.PurposeModelResolver;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.PurposeResolveResult;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.TrialFailure;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 从绑定表装配一轮 ReAct。所属域：runtime。只读 agent / model / tool / skill，不改配置。
 * 主对话走 CHAT 槽（priority 槽内降级）；Router 判定先 ROUTER 槽再降 CHAT。
 */
@Component
public class TrialBindingLoader {

    private final PurposeModelResolver purposes;
    private final ModelInstanceRepository instances;
    private final ModelProviderRepository providers;
    private final ModelCredentialRepository credentials;
    private final TrialPromptFactory promptFactory;
    private final RuntimeLimitResolver limitResolver;

    /**
     * @param purposes      用途槽解析
     * @param instances     模型实例
     * @param providers     供应商
     * @param credentials   凭证，只检查存在和启停，解密留给模型工厂
     * @param promptFactory 按预算拼提示词和工具 schema
     * @param limitResolver Agent 覆盖与 YAML 合成生效限制
     */
    public TrialBindingLoader(PurposeModelResolver purposes, ModelInstanceRepository instances,
                              ModelProviderRepository providers, ModelCredentialRepository credentials,
                              TrialPromptFactory promptFactory, RuntimeLimitResolver limitResolver) {
        this.purposes = purposes;
        this.instances = instances;
        this.providers = providers;
        this.credentials = credentials;
        this.promptFactory = promptFactory;
        this.limitResolver = limitResolver;
    }

    /**
     * 装配一个已启用的 REACT Agent（Router 子节点路径）。
     *
     * @param agent     定义，调用方已确认存在
     * @param userQuery 子节点的本轮用户输入（sub_query 或回退的整句）；命中 Skill 触发关键词时强制注入
     * @return 计划
     * @throws TrialFailure 未启用、不是 REACT，或没有对话模型
     */
    public TrialPlan loadReact(AgentDef agent, String userQuery) {
        if (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED) {
            throw new TrialFailure("Agent 未启用");
        }
        if (!AgentRunMode.REACT.name().equals(agent.getRunMode())) {
            throw new TrialFailure("子 Agent 必须是 REACT");
        }
        return loadSingleAgent(agent, userQuery);
    }

    /**
     * 装配「单 Agent 直接执行」的计划，REACT 与 PLAN_AND_EXECUTE 共用。
     * 两种模式的模型槽（CHAT）与工具/技能/记忆装配完全一致；
     * 差异只在于 PLAN_AND_EXECUTE 每一步执行前会换一段系统提示，这一步由调用方覆盖。
     *
     * @param agent     定义，调用方已确认存在
     * @param userQuery 本轮用户输入；用于判定 Skill 触发关键词，命中则强制注入该 Skill 正文
     * @return 计划
     * @throws TrialFailure 未启用、不是 REACT / PLAN_AND_EXECUTE，或没有对话模型
     */
    public TrialPlan loadSingleAgent(AgentDef agent, String userQuery) {
        if (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED) {
            throw new TrialFailure("Agent 未启用");
        }
        if (!AgentRunMode.bindsSingleAgent(agent.getRunMode())) {
            throw new TrialFailure("只有 ReAct 或计划执行模式的 Agent 可直接执行");
        }
        Long modelId = findChatModel(agent.getId()).orElseThrow(() -> new TrialFailure("Agent 没有可用的对话模型"));
        TrialPlan plan = new TrialPlan();
        plan.setAgentId(agent.getId());
        plan.setAgentName(agent.getName());
        plan.setRunMode(agent.getRunMode());
        plan.setModelInstanceId(modelId);
        TrialPromptFactory.Built built = promptFactory.build(agent, userQuery);
        plan.setSystemPrompt(built.prompt());
        plan.setTools(built.tools());
        plan.setMcpTools(built.mcpTools());
        plan.setInjection(built.stats());
        plan.getNotices().addAll(built.notices());
        EffectiveLimits limits = limitResolver.resolve(agent);
        plan.setLimits(limits);
        return plan;
    }

    /**
     * 在已有计划上收紧墙钟（Router 父超时剩余）。
     *
     * @param plan      子 Agent 计划
     * @param timeoutMs 新墙钟
     * @return 同一 plan
     */
    public TrialPlan tightenTimeout(TrialPlan plan, long timeoutMs) {
        if (plan.getLimits() != null) {
            plan.setLimits(plan.getLimits().withTimeoutMs(timeoutMs));
        }
        return plan;
    }

    /**
     * CHAT 槽内按 priority 依次尝试启用且凭证齐全的实例。
     *
     * @param agentId Agent
     * @return 可用实例 id
     */
    public Optional<Long> findChatModel(Long agentId) {
        return firstWiredInSlot(agentId, ModelCapability.CHAT);
    }

    /**
     * Router 父判定：先 ROUTER 槽，耗尽再降 CHAT 槽。
     *
     * @param agentId 父 Agent
     * @return 可用实例 id
     */
    public Optional<Long> findRouterModel(Long agentId) {
        Optional<Long> router = firstWiredInSlot(agentId, ModelCapability.ROUTER);
        if (router.isPresent()) {
            return router;
        }
        return findChatModel(agentId);
    }

    /**
     * 解析 VISION 槽的可用实例。只认 VISION 槽，<b>不允许降级到 CHAT</b>：
     * 文本模型看不懂图，降级过去只会把图片当成乱码喂进去，得到一个更糟的答案。
     *
     * @param agentId Agent
     * @return 可用的视觉实例 id；未绑定或凭证不全时为空
     */
    public Optional<Long> findVisionModel(Long agentId) {
        return firstWiredInSlot(agentId, ModelCapability.VISION);
    }

    /**
     * 本轮是否需要视觉预处理，以及所需实例是否就绪。集中在一处判断，
     * 让「前置校验」与「OCR 执行」用同一套口径，避免校验放过了执行又拒。
     *
     * @param agentId Agent
     * @return 视觉实例 id；无图需求时调用方不会走这里
     * @throws TrialFailure VISION 槽未绑定可用模型（文案直接引导用户去配置）
     */
    public Long requireVisionModel(Long agentId) {
        return findVisionModel(agentId).orElseThrow(() -> new TrialFailure(
                "当前 Agent 未配置视觉识别(OCR)模型，无法处理图片。请前往「模型配置」Tab 绑定 VISION 模型。"));
    }

    /**
     * 前置校验：对话主模型必须在。带图与否都要查——没有 CHAT 模型时任何一轮都跑不起来，
     * 提前用明确文案拒绝，好过让后续装配抛一句「Agent 没有可用的对话模型」。
     *
     * @param agentId Agent
     * @return CHAT 槽可用实例 id
     * @throws TrialFailure CHAT 槽未绑定可用模型
     */
    public Long requireChatModel(Long agentId) {
        return findChatModel(agentId).orElseThrow(() -> new TrialFailure(
                "当前 Agent 未配置对话(CHAT)模型，无法执行。请前往「模型配置」Tab 绑定 CHAT 模型。"));
    }

    /**
     * 解析详情（含 failover_priority / degraded_purpose），供观测。
     *
     * @param agentId Agent
     * @param purpose 用途
     * @return 解析结果；无可用则空
     */
    public Optional<PurposeResolveResult> resolvePurpose(Long agentId, ModelCapability purpose) {
        boolean degrade = purpose != null && purpose.allowsChatDegrade();
        Optional<PurposeResolveResult> hit = purposes.resolve(agentId, purpose, null, null, degrade);
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        // 再确认凭证齐全，否则视同不可用并尝试槽内下一个（仅 SLOT 源时）
        if (wired(hit.get().getInstanceId())) {
            return hit;
        }
        return firstWiredInSlot(agentId, purpose).map(id -> PurposeResolveResult.builder()
                .instanceId(id)
                .requestedPurpose(purpose.name())
                .source("SLOT")
                .build());
    }

    private Optional<Long> firstWiredInSlot(Long agentId, ModelCapability purpose) {
        List<AgentModelBind> slot = purposes.listSlotBinds(agentId, purpose);
        for (AgentModelBind bind : slot) {
            if (bind.getStatus() == null || bind.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            if (wired(bind.getModelInstanceId())) {
                return Optional.of(bind.getModelInstanceId());
            }
        }
        return Optional.empty();
    }

    private boolean wired(long instanceId) {
        Optional<ModelInstance> instance = instances.findById(instanceId)
                .filter(item -> RecordStatus.isEnabled(item.getStatus()));
        if (instance.isEmpty()) {
            return false;
        }
        Optional<ModelProvider> provider = providers.findById(instance.get().getProviderId())
                .filter(item -> RecordStatus.isEnabled(item.getStatus()));
        Optional<ModelCredential> credential = credentials.findById(instance.get().getCredentialId())
                .filter(item -> RecordStatus.isEnabled(item.getStatus()))
                .filter(item -> item.getApiKeyCipher() != null && !item.getApiKeyCipher().isBlank());
        return provider.isPresent() && credential.isPresent();
    }
}

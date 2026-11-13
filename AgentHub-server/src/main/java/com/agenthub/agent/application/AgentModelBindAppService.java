package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.ModelBindCreateReq;
import com.agenthub.agent.api.dto.ModelBindResp;
import com.agenthub.agent.api.dto.ModelBindUpdateReq;
import com.agenthub.agent.api.dto.PromptBindingReq;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.model.AgentPromptBinding;
import com.agenthub.agent.domain.model.AgentPromptTemplateOption;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.agent.domain.service.AgentPromptTemplatePort;
import com.agenthub.agent.domain.service.PurposeSlotRules;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型绑定用例。所属域：agent。
 * 按有效用途分槽，每槽最多 {@link PurposeSlotRules#MAX_PER_SLOT}；CHAT 至少保留 1；ROUTER 仅 ROUTER 模式。
 */
@Service
public class AgentModelBindAppService {

    /** 默认绑定的语言；与 AgentAppService 保持一致（L2 按 purpose+locale 唯一生效）。 */
    private static final String DEFAULT_PROMPT_LOCALE = "zh-CN";

    private final AgentLookup lookup;
    private final AgentModelBindRepository binds;
    private final AgentRefLookup refs;
    private final PurposeModelResolver purposeResolver;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    /** L3 提示词端口：非 CHAT 用途的模板读取 / 校验 / 回显 */
    private final AgentPromptTemplatePort promptTemplates;

    /**
     * @param lookup           确认 Agent 还在
     * @param binds            绑定仓储
     * @param refs             模型实例只读引用
     * @param purposeResolver  用途槽列表与排序
     * @param operatorContext  操作人
     * @param operLogWriter    操作日志
     * @param promptTemplates  L3 提示词端口
     */
    public AgentModelBindAppService(AgentLookup lookup, AgentModelBindRepository binds, AgentRefLookup refs,
                                    PurposeModelResolver purposeResolver, OperatorContext operatorContext,
                                    OperLogWriter operLogWriter, AgentPromptTemplatePort promptTemplates) {
        this.lookup = lookup;
        this.binds = binds;
        this.refs = refs;
        this.purposeResolver = purposeResolver;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.promptTemplates = promptTemplates;
    }

    /**
     * @param agentId Agent 主键
     * @return 该 Agent 的模型绑定（含有效用途与槽内序号）
     */
    public List<ModelBindResp> list(Long agentId) {
        lookup.require(agentId);
        List<AgentModelBind> rows = binds.listByAgentId(agentId);
        Map<String, List<AgentModelBind>> byPurpose = new HashMap<>();
        Map<Long, ModelInstance> instanceCache = new HashMap<>();
        for (AgentModelBind bind : rows) {
            ModelInstance instance = instanceCache.computeIfAbsent(bind.getModelInstanceId(),
                    id -> refs.findModel(id).orElse(null));
            Optional<ModelCapability> purpose = PurposeSlotRules.effectivePurpose(bind, instance);
            purpose.ifPresent(p -> byPurpose.computeIfAbsent(p.name(), k -> purposeResolver.listSlotBinds(agentId, p)));
        }
        return rows.stream().map(bind -> {
            ModelInstance instance = instanceCache.get(bind.getModelInstanceId());
            Optional<ModelCapability> purpose = PurposeSlotRules.effectivePurpose(bind, instance);
            Integer rank = null;
            String effective = null;
            if (purpose.isPresent()) {
                effective = purpose.get().name();
                List<AgentModelBind> slot = byPurpose.getOrDefault(effective, List.of());
                int r = PurposeSlotRules.slotRank(slot, bind.getId());
                rank = r > 0 ? r : null;
            }
            return toResp(bind, instance, effective, rank);
        }).toList();
    }

    /**
     * @param agentId Agent 主键
     * @param req     实例、用途和优先级
     * @return 新绑定
     * @throws BizException 实例不可用、重复、槽满或 ROUTER 模式不符
     */
    @Transactional
    public ModelBindResp create(Long agentId, ModelBindCreateReq req) {
        AgentDef agent = lookup.require(agentId);
        ModelInstance instance = refs.requireModel(req.getModelInstanceId(), true);
        if (binds.existsPair(agentId, instance.getId(), null)) {
            throw new BizException(ErrorCode.DUPLICATE, "该模型已绑定");
        }
        String purpose = AgentChecks.purpose(req.getPurpose());
        AgentModelBind probe = new AgentModelBind();
        probe.setPurpose(purpose);
        ModelCapability slot = PurposeSlotRules.effectivePurpose(probe, instance)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "无法解析用途槽"));
        // 嵌入不在 Agent 直绑：与知识库/向量实例选型冲突，且运行时也不按 Agent 槽取嵌入模型
        assertAgentBindable(slot);
        // 二次校验：禁止用 purpose 覆盖把 Embedding 塞进 RERANK 等错槽
        assertInstanceMatchesSlot(instance, slot);
        assertModeAllows(slot, agent.getRunMode());
        assertSlotRoom(agentId, slot, null);
        AgentModelBind bind = new AgentModelBind();
        bind.setAgentId(agentId);
        bind.setModelInstanceId(instance.getId());
        bind.setPurpose(purpose);
        bind.setPriority(AgentChecks.priority(req.getPriority()));
        bind.setStatus(AgentChecks.initialStatus(req.getStatus()));
        // 非 CHAT 的 L3：四列跟着绑定行走 agent_model_bind；未显式选模板时钉该用途当前生效版本
        applyPromptBinding(bind, slot, req.getPrompt(), true);
        Long id = binds.insert(bind, operatorContext.requireUserId());
        operLogWriter.write("agent", "bind", "agent_model_bind", String.valueOf(id),
                "绑定模型 " + instance.getDisplayName() + " → " + slot.name());
        return list(agentId).stream().filter(r -> id.equals(r.getId())).findFirst()
                .orElseThrow();
    }

    /**
     * 重新启用时再次确认实例仍是启用的。改用途时校验目标槽容量与 CHAT 下限。
     *
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @param req     用途、优先级和启停
     * @return 更新后的绑定
     */
    @Transactional
    public ModelBindResp update(Long agentId, Long bindId, ModelBindUpdateReq req) {
        AgentDef agent = lookup.require(agentId);
        AgentModelBind existing = requireOwned(agentId, bindId);
        int status = AgentChecks.exactStatus(req.getStatus());
        ModelInstance instance = refs.requireModel(existing.getModelInstanceId(), status == AgentStatus.ENABLED);
        String purpose = AgentChecks.purpose(req.getPurpose());
        AgentModelBind probe = new AgentModelBind();
        probe.setPurpose(purpose);
        ModelCapability newSlot = PurposeSlotRules.effectivePurpose(probe, instance)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "无法解析用途槽"));
        // 改用途同样不许把绑定改成 EMBEDDING（历史遗留行只允许移除，不允许再写回）
        assertAgentBindable(newSlot);
        // 改用途时同样要求实例 capability 与目标槽一致
        assertInstanceMatchesSlot(instance, newSlot);
        assertModeAllows(newSlot, agent.getRunMode());
        ModelCapability oldSlot = PurposeSlotRules.effectivePurpose(existing, instance).orElse(null);
        if (oldSlot != newSlot) {
            assertSlotRoom(agentId, newSlot, bindId);
            if (oldSlot != null && PurposeSlotRules.requiresAtLeastOne(oldSlot)
                    && countSlot(agentId, oldSlot, bindId) < 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "CHAT 槽至少保留 1 个绑定");
            }
        }
        existing.setPurpose(purpose);
        existing.setPriority(AgentChecks.priority(req.getPriority()));
        existing.setStatus(status);
        // L3：显式传了 prompt 就按新槽重新校验；改用途但没传 prompt 时，也按新槽复核旧值，
        // 否则会出现「用途换成 REWRITE、模板还钉着 SUMMARY」的错配，运行时组装直接失败
        if (req.getPrompt() != null) {
            applyPromptBinding(existing, newSlot, req.getPrompt(), true);
        } else if (oldSlot != newSlot) {
            revalidatePromptForNewSlot(existing, newSlot);
        }
        binds.update(existing, operatorContext.requireUserId());
        operLogWriter.write("agent", "bind", "agent_model_bind", String.valueOf(bindId),
                "更新模型绑定 " + instance.getDisplayName());
        return list(agentId).stream().filter(r -> bindId.equals(r.getId())).findFirst()
                .orElseThrow();
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     */
    @Transactional
    public void remove(Long agentId, Long bindId) {
        lookup.require(agentId);
        AgentModelBind existing = requireOwned(agentId, bindId);
        ModelInstance instance = refs.findModel(existing.getModelInstanceId()).orElse(null);
        Optional<ModelCapability> slot = PurposeSlotRules.effectivePurpose(existing, instance);
        if (slot.isPresent() && PurposeSlotRules.requiresAtLeastOne(slot.get())
                && countSlot(agentId, slot.get(), bindId) < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "CHAT 槽至少保留 1 个绑定");
        }
        binds.logicalDelete(bindId, operatorContext.requireUserId());
        operLogWriter.write("agent", "unbind", "agent_model_bind", String.valueOf(bindId),
                "移除模型绑定 " + existing.getModelInstanceId());
    }

    /**
     * 槽用途必须与实例 capability 一致（旧码已规范化后比较）。
     *
     * @param instance 已校验存在的实例
     * @param slot     有效用途槽
     */
    private void assertInstanceMatchesSlot(ModelInstance instance, ModelCapability slot) {
        if (PurposeSlotRules.instanceMatchesSlot(instance, slot)) {
            return;
        }
        String actual = ModelCapability.tryParse(instance.getCapability())
                .map(Enum::name)
                .orElse(instance.getCapability() == null || instance.getCapability().isBlank()
                        ? "空" : instance.getCapability());
        throw new BizException(ErrorCode.BAD_REQUEST,
                "模型实例用途为 " + actual + "，与槽 " + slot.name() + " 不一致");
    }

    private void assertModeAllows(ModelCapability slot, String runMode) {
        if (!PurposeSlotRules.purposeAllowedForMode(slot, runMode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ROUTER 用途仅可在 Router 模式下绑定");
        }
    }

    /**
     * 槽用途必须能在 Agent 侧绑定。
     * <p>
     * EMBEDDING：嵌入由知识库 / 向量实例选型并锁定维度，Agent 绑了也不参与运行时。
     * RERANK：属检索链路，配置入口唯一在 Agent 详情「知识库」Tab（走 rag 域接口），
     * 这里拒绝是为了不出现第二个写入口；历史行仍可移除。
     *
     * @param slot 目标用途槽
     */
    private void assertAgentBindable(ModelCapability slot) {
        if (PurposeSlotRules.agentBindable(slot)) {
            return;
        }
        if (slot == ModelCapability.EMBEDDING) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "嵌入模型不在 Agent 侧绑定：请到「知识库」选型（向量实例与维度在那里锁定）");
        }
        if (slot == ModelCapability.RERANK) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "重排模型请在 Agent 详情「知识库」Tab 的检索链路中配置");
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "该用途不支持在 Agent 侧绑定");
    }

    private void assertSlotRoom(Long agentId, ModelCapability slot, Long excludeBindId) {
        int capacity = PurposeSlotRules.slotCapacity(slot);
        if (countSlot(agentId, slot, excludeBindId) >= capacity) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    slot.name() + " 槽最多 " + capacity + " 个绑定");
        }
    }

    private int countSlot(Long agentId, ModelCapability slot, Long excludeBindId) {
        return (int) purposeResolver.listSlotBinds(agentId, slot).stream()
                .filter(b -> excludeBindId == null || !excludeBindId.equals(b.getId()))
                .count();
    }

    private AgentModelBind requireOwned(Long agentId, Long bindId) {
        AgentModelBind bind = binds.findById(bindId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "模型绑定不存在"));
        if (!agentId.equals(bind.getAgentId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型绑定不存在");
        }
        return bind;
    }

    private ModelBindResp toResp(AgentModelBind bind, ModelInstance instance, String effectivePurpose, Integer slotRank) {
        ModelBindResp resp = new ModelBindResp();
        resp.setId(bind.getId());
        resp.setAgentId(bind.getAgentId());
        resp.setModelInstanceId(bind.getModelInstanceId());
        resp.setPurpose(bind.getPurpose());
        resp.setEffectivePurpose(effectivePurpose);
        resp.setSlotRank(slotRank);
        resp.setPriority(bind.getPriority());
        resp.setStatus(bind.getStatus());
        // 用途无模板维度（VISION / ASR / GUARDRAIL…）时返回 null，前端据此隐藏「提示词策略」
        if (effectivePurpose != null && promptTemplates.supportsPurpose(effectivePurpose)) {
            resp.setPrompt(AgentPromptDtos.toResp(promptTemplates, effectivePurpose, bind.getPromptTemplateId(),
                    bind.getTemplateVersion(), bind.getPromptOverrideJson(), bind.getPromptExtra()));
        }
        if (instance != null) {
            resp.setDisplayName(instance.getDisplayName());
            resp.setModelId(instance.getModelId());
            resp.setCapability(instance.getCapability());
            resp.setTargetStatus(instance.getStatus());
        }
        return resp;
    }

    /**
     * 把 L3 四列写进绑定行（非 CHAT 挂载点）。
     * <p>
     * 用途取**有效槽**而非请求里的 purpose 字符串：purpose 可以留空（跟着实例能力走），
     * 若拿原始空串去校验模板用途，会把合法的「跟实例」配置判成用途不符。
     *
     * @param bind  正在保存的绑定行
     * @param slot  有效用途槽
     * @param req   前端提交的四列；null 表示不挂模板
     * @param autoBind true=未选模板时钉该用途当前生效版本（新建 / 显式清空）
     * @throws com.agenthub.common.error.BizException 段落键非法、core 段、版本不一致或用途不符
     */
    private void applyPromptBinding(AgentModelBind bind, ModelCapability slot, PromptBindingReq req,
                                    boolean autoBind) {
        String purpose = slot == null ? null : slot.name();
        // 显式选了模板：沿用既有硬闸
        if (req != null && req.getTemplateId() != null) {
            apply(bind, promptTemplates.normalize(purpose, req.getTemplateId(),
                    req.getTemplateVersion(), req.getOverride(), req.getPromptExtra()));
            return;
        }
        // 未选模板：该用途有已发布生效 L2 就自动钉住，禁止回到「未挂模板」
        if (autoBind && promptTemplates.supportsPurpose(purpose)) {
            AgentPromptTemplateOption def = promptTemplates.defaultOption(purpose, DEFAULT_PROMPT_LOCALE);
            if (def != null) {
                // 追加要求优先用本次请求的；没传则保留绑定行已有值，避免改用途时丢追加
                String extra = req == null ? bind.getPromptExtra() : req.getPromptExtra();
                apply(bind, promptTemplates.normalize(purpose, def.getId(), def.getVersion(), null, extra));
                return;
            }
        }
        // 该用途没有生效模板：保持未挂，交给运行时「用途未配置模板」失败语义（禁止用 CHAT 顶替）
        if (req != null) {
            bind.setPromptTemplateId(null);
            bind.setTemplateVersion(null);
            bind.setPromptOverrideJson(null);
            bind.setPromptExtra(null);
        }
    }

    /**
     * 四列落库。显式选择与默认绑定两条路径共用，避免两处各写一份 setter。
     *
     * @param bind    绑定行
     * @param binding 已通过保存闸的绑定值
     */
    private static void apply(AgentModelBind bind, AgentPromptBinding binding) {
        bind.setPromptTemplateId(binding.getTemplateId());
        bind.setTemplateVersion(binding.getTemplateVersion());
        bind.setPromptOverrideJson(binding.getOverrideJson());
        bind.setPromptExtra(binding.getPromptExtra());
    }

    /**
     * 改用途但未重传 L3 时的复核：用新槽重新跑一次保存闸。
     * <p>
     * 未挂模板（templateId 为空）则直接跳过——这既是最常见情况，也避免为「清空态」造错。
     * 已挂模板但新槽不支持（如从 CHAT 改到 VISION）直接拒绝，而不是静默丢掉模板配置。
     *
     * @param bind 绑定行（模板字段为改用途前的值）
     * @param slot 新的有效用途槽
     */
    private void revalidatePromptForNewSlot(AgentModelBind bind, ModelCapability slot) {
        String purpose = slot == null ? null : slot.name();
        if (bind.getPromptTemplateId() == null) {
            // 改到新用途且原本未挂模板：该用途有生效模板就补钉，避免「换了用途却没有提示词」
            if (promptTemplates.supportsPurpose(purpose)) {
                AgentPromptTemplateOption def = promptTemplates.defaultOption(purpose, DEFAULT_PROMPT_LOCALE);
                if (def != null) {
                    apply(bind, promptTemplates.normalize(purpose, def.getId(), def.getVersion(), null,
                            bind.getPromptExtra()));
                }
            }
            return;
        }
        if (!promptTemplates.supportsPurpose(purpose)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该用途不支持提示词模板，请先清空提示词配置再改用途");
        }
        Map<String, String> override = promptTemplates.readOverrideSafe(bind.getPromptOverrideJson());
        AgentPromptBinding binding = promptTemplates.normalize(purpose, bind.getPromptTemplateId(),
                bind.getTemplateVersion(), override, bind.getPromptExtra());
        bind.setPromptTemplateId(binding.getTemplateId());
        bind.setTemplateVersion(binding.getTemplateVersion());
        bind.setPromptOverrideJson(binding.getOverrideJson());
        bind.setPromptExtra(binding.getPromptExtra());
    }
}

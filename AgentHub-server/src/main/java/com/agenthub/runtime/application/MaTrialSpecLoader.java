package com.agenthub.runtime.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.domain.service.MaTenantProvider;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.service.TrialFailure;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 把 {@code ma_def} 方案解析成一次试跑的执行来源。所属域：runtime。
 *
 * <p>为什么单独一层：虚拟根没有 Agent 可读，绑根又要「方案优先、没方案才回落旧配置」。
 * 把「读方案 / 校验启停与租户 / 解析判路由模型 / 过滤启用候选」集中在这里，
 * Router 执行器只认 {@link MaRunSpec}，不再自己决定配置从哪儿来。
 *
 * <p>读源纪律（MA-P2 刀2）：
 * <ul>
 *   <li>有 {@code ma_def_id} 的会话（虚拟根，或从方案页直接打开）→ 一律读方案；</li>
 *   <li>绑根会话没有 {@code ma_def_id} → 开跑时按「该 ROUTER 当前启用中的方案」实时解析，
 *       因此改方案候选不经旧编排 Tab 也会影响下一轮；</li>
 *   <li>都没有 → 返回 {@code null}，执行器回落 Agent 侧旧配置并在时间线给出提醒。</li>
 * </ul>
 *
 * <p>线程：无状态，可被并行子线程之外的请求线程直接调用；不在执行中途缓存方案，
 * 保证「一轮执行内候选不变、跨轮取最新」。
 */
@Component
public class MaTrialSpecLoader {

    private final MaDefRepository mas;
    private final MaTenantProvider tenantProvider;
    private final ModelInstanceRepository instances;

    /**
     * @param mas            方案与候选
     * @param tenantProvider 当前租户；一期由配置提供
     * @param instances      判路由模型实例校验
     */
    public MaTrialSpecLoader(MaDefRepository mas, MaTenantProvider tenantProvider,
                             ModelInstanceRepository instances) {
        this.mas = mas;
        this.tenantProvider = tenantProvider;
        this.instances = instances;
    }

    /**
     * 新建虚拟根试跑会话前的方案校验。
     *
     * <p>只对虚拟根（{@code root_agent_id IS NULL}）开放：绑根方案的执行上下文是 Agent
     * （记忆窗口、视觉槽、计量归属都挂在它上面），必须从 Agent 入口开跑，
     * 强行从方案页进会得到一个「没有 Agent 但方案又要求 Agent」的会话，故在此明确拒绝。
     *
     * @param maId 方案主键
     * @return 执行来源（候选已过滤）
     * @throws TrialFailure 不存在、跨租户、已停用，或该方案绑定了 ROUTER
     */
    public MaRunSpec requireForCreate(Long maId) {
        return build(requireUsable(maId), null);
    }

    /**
     * 开跑时解析本轮执行来源。
     *
     * @param session 当前会话（可能带 ma_def_id）
     * @param agent   会话对应的 Agent；虚拟根为 null
     * @return 方案来源；null 表示没有方案，调用方回落 Agent 旧配置
     * @throws TrialFailure 会话指向的方案已删除 / 跨租户 / 已停用
     */
    public MaRunSpec resolve(RunSession session, AgentDef agent) {
        if (session == null) {
            return null;
        }
        if (session.getMaDefId() != null) {
            return build(requireUsable(session.getMaDefId()), agent);
        }
        // 绑根：按当前启用方案实时解析，避免沿用建会话那一刻的旧候选
        if (agent != null && agent.getId() != null
                && AgentRunMode.ROUTER.name().equals(agent.getRunMode())) {
            Optional<MaDef> bound = mas.findEnabledByRoot(tenant(), agent.getId());
            return bound.map(def -> build(def, agent)).orElse(null);
        }
        return null;
    }

    /**
     * 详情 / 列表侧查询：某 ROUTER 当前启用的方案。
     *
     * @param rootAgentId ROUTER 主键
     * @return 方案来源；没有返回空
     */
    public Optional<MaRunSpec> findForRoot(Long rootAgentId) {
        if (rootAgentId == null) {
            return Optional.empty();
        }
        return mas.findEnabledByRoot(tenant(), rootAgentId).map(def -> build(def, null));
    }

    /**
     * 按 id 取方案并做「本租户 + 启用」校验。
     *
     * @param maId 方案主键
     * @return 已启用方案
     * @throws TrialFailure 不存在、跨租户或已停用
     */
    private MaDef requireUsable(Long maId) {
        MaDef def = mas.findById(maId)
                .orElseThrow(() -> new TrialFailure("编排方案不存在或已删除，请回到多智能体列表重新选择"));
        // 跨租户按 404 口径处理，不回显别的租户方案是否存在
        if (def.getTenantId() == null || def.getTenantId() != tenant()) {
            throw new TrialFailure("编排方案不存在或不属于当前租户");
        }
        if (def.getEnabled() == null || def.getEnabled() != 1) {
            throw new TrialFailure("编排方案「" + display(def) + "」已停用，无法试跑；请先启用方案");
        }
        return def;
    }

    /**
     * 方案 + 可选 Agent 上下文 → 执行来源。
     *
     * @param def   已通过可用性校验的方案
     * @param agent 会话对应的 Agent；虚拟根传 null
     * @return 执行来源
     */
    private MaRunSpec build(MaDef def, AgentDef agent) {
        if (def.getRootAgentId() != null && agent == null) {
            // 绑根方案只允许从 Agent 入口进入；从方案页直连时也必须带上 agentId 才安全
            throw new TrialFailure("该方案绑定了 ROUTER，请从对应 Agent 的试跑入口打开");
        }
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse(def.getConfigJson(), AgentRunMode.ROUTER.name());
        // 顶层 template 是方案的权威值：config_json 里的同名字段只作镜像，避免两处漂移
        runtime.setMaTemplate(def.getTemplate());
        List<MaCandidate> candidates = mas.listCandidates(def.getId()).stream()
                .filter(item -> item.getEnabled() != null && item.getEnabled() == 1)
                .toList();
        Long judge = resolveJudgeModel(def, runtime);
        return MaRunSpec.of(def, candidates, judge, runtime);
    }

    /**
     * 解析判路由 / 汇总模型。
     *
     * <p>字段优先级（写入交付对照表）：
     * <ol>
     *   <li>{@code config_json.maJudgeModelInstanceId}：{@code model_instance.id}，首选；</li>
     *   <li>{@code config_json.maJudgeModel}：纯数字按实例 id；否则按启用中 ROUTER / CHAT
     *       实例的 {@code model_id} 或展示名大小写不敏感匹配；</li>
     *   <li>都没有：绑根返回 null（执行器回落 Agent 的 ROUTER / CHAT 槽）；
     *       虚拟根也返回 null，由执行器在真正需要判路由时给出中文失败。</li>
     * </ol>
     *
     * @param def     方案（用于文案）
     * @param runtime 方案配置
     * @return 可用的模型实例 id；无配置返回 null
     * @throws TrialFailure 明确配置了但实例不存在 / 已停用 / 名称匹配不到
     */
    private Long resolveJudgeModel(MaDef def, AgentRuntimeConfig runtime) {
        Long configured = runtime.getMaJudgeModelInstanceId();
        if (configured != null) {
            return requireEnabledInstance(def, configured);
        }
        String alias = runtime.getMaJudgeModel();
        if (alias == null || alias.isBlank()) {
            return null;
        }
        String text = alias.trim();
        if (text.chars().allMatch(Character::isDigit) && text.length() <= 18) {
            return requireEnabledInstance(def, Long.valueOf(text));
        }
        String wanted = text.toLowerCase(Locale.ROOT);
        for (ModelCapability capability : List.of(ModelCapability.ROUTER, ModelCapability.CHAT)) {
            for (ModelInstance instance : instances.listEnabledByCapability(capability.name())) {
                if (matches(instance, wanted)) {
                    return instance.getId();
                }
            }
        }
        throw new TrialFailure("方案「" + display(def) + "」配置的判路由模型「" + text
                + "」不存在或未启用，请到模型配置里确认");
    }

    /**
     * 校验实例存在且启用。
     *
     * @param def        方案（文案）
     * @param instanceId 实例 id
     * @return 同一 id
     * @throws TrialFailure 不存在或已停用
     */
    private Long requireEnabledInstance(MaDef def, Long instanceId) {
        ModelInstance instance = instances.findById(instanceId).orElse(null);
        if (instance == null || !RecordStatus.isEnabled(instance.getStatus())) {
            throw new TrialFailure("方案「" + display(def) + "」配置的判路由模型实例 #" + instanceId
                    + " 不存在或已停用，请重新选择");
        }
        return instanceId;
    }

    /**
     * 名称匹配：model_id 与展示名都认，大小写不敏感。
     */
    private static boolean matches(ModelInstance instance, String wanted) {
        return equalsIgnoreCase(instance.getModelId(), wanted)
                || equalsIgnoreCase(instance.getDisplayName(), wanted);
    }

    private static boolean equalsIgnoreCase(String raw, String wanted) {
        return raw != null && raw.trim().toLowerCase(Locale.ROOT).equals(wanted);
    }

    private long tenant() {
        return tenantProvider.currentTenantId();
    }

    /** 错误文案里的方案称呼：名称 → 编码 → 主键。 */
    private static String display(MaDef def) {
        if (def.getName() != null && !def.getName().isBlank()) {
            return def.getName().trim();
        }
        if (def.getCode() != null && !def.getCode().isBlank()) {
            return def.getCode().trim();
        }
        return "#" + def.getId();
    }
}

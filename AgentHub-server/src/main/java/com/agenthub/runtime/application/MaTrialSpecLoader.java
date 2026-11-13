package com.agenthub.runtime.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.domain.service.MaTenantProvider;
import com.agenthub.agent.domain.service.RoutePoolDedupPolicy;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.service.TrialFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把 {@code ma_def} 方案解析成一次试跑的执行来源。所属域：runtime。
 *
 * <p><b>方案就是自己的根</b>（本刀）：解析不再读 {@code root_agent_id}，也不再为「空根」合成 Agent 壳。
 * 把「读方案 / 校验启停与租户 / 解析判路由模型 / 过滤启用候选」集中在这里，
 * Router 执行器只认 {@link MaRunSpec}，配置从哪儿来不在执行器里判断。
 *
 * <p>读源纪律：
 * <ul>
 *   <li>有 {@code ma_def_id} 的会话（方案页直连 / 工作台）→ 一律读方案；</li>
 *   <li>没有方案的历史会话 → 返回 {@code null}，执行器回落 Agent 侧旧配置并在时间线给出提醒；</li>
 *   <li>普通方案与入口 Router 走完全相同的装载路径，不存在「绑根方案只允许从 Agent 入口进」的额外限制。</li>
 * </ul>
 *
 * <p>线程：无状态，可被并行子线程之外的请求线程直接调用；不在执行中途缓存方案，
 * 保证「一轮执行内候选不变、跨轮取最新」。
 */
@Component
public class MaTrialSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(MaTrialSpecLoader.class);

    private final MaDefRepository mas;
    private final MaTenantProvider tenantProvider;
    private final ModelInstanceRepository instances;
    /** 刀5B：工作流候选 → 只读执行来源；装载时校验发布 / 说明 / 租户 */
    private final WorkflowRunSpecLoader workflowTargets;

    /**
     * @param mas              方案与候选
     * @param tenantProvider   当前租户；一期由配置提供
     * @param instances        判路由模型实例校验
     * @param workflowTargets  工作流目标装载（刀5B）
     */
    public MaTrialSpecLoader(MaDefRepository mas, MaTenantProvider tenantProvider,
                             ModelInstanceRepository instances,
                             WorkflowRunSpecLoader workflowTargets) {
        this.mas = mas;
        this.tenantProvider = tenantProvider;
        this.instances = instances;
        this.workflowTargets = workflowTargets;
    }

    /**
     * 从方案页 / 工作台新建试跑会话前的方案校验。
     *
     * <p>普通方案与入口 Router 一致：没有 Agent 根可读，模型槽与记忆策略都不挂在 Agent 上。
     *
     * @param maId 方案主键
     * @return 执行来源（候选已过滤）
     * @throws TrialFailure 不存在、跨租户或已停用
     */
    public MaRunSpec requireForCreate(Long maId) {
        return build(requireUsable(maId));
    }

    /**
     * 工作台默认接待建会话前的方案校验。
     *
     * <p>与 {@link #requireForCreate(Long)} 同一条装载路径：方案以自己为根，
     * 不再按 {@code root_agent_id} 解析根 Agent，也不会因为历史残留的根值而拒绝建会话。
     *
     * @param maId 方案主键
     * @return 执行来源（候选已过滤；判路由模型可为空，由 runtime 回落平台槽）
     * @throws TrialFailure 不存在、跨租户或已停用
     */
    public MaRunSpec requireForWorkbench(Long maId) {
        return build(requireUsable(maId));
    }

    /**
     * 开跑时解析本轮执行来源。
     *
     * @param session 当前会话（可能带 ma_def_id）
     * @param agent   会话对应的 Agent；方案会话为 null，仅旧 Agent 试跑会传值
     * @return 方案来源；null 表示没有方案，调用方回落 Agent 旧配置
     * @throws TrialFailure 会话指向的方案已删除 / 跨租户 / 已停用
     */
    public MaRunSpec resolve(RunSession session, AgentDef agent) {
        if (session == null || session.getMaDefId() == null) {
            // 方案会话一律带 ma_def_id；没有说明是旧 Agent 试跑，读源保持在 Agent 侧
            return null;
        }
        return build(requireUsable(session.getMaDefId()));
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
     * 方案 → 执行来源。
     *
     * <p>方案就是自己的根：不再需要 Agent 上下文，也不再因为历史 {@code root_agent_id} 拒绝装载。
     *
     * @param def 已通过可用性校验的方案
     * @return 执行来源
     */
    private MaRunSpec build(MaDef def) {
        return buildLevel(def, 0, new HashSet<>());
    }

    /**
     * 按嵌套深度装载一份方案。
     *
     * <p>深度从正在运行的根算起：根是 0，它挂的方案是 1。超过 {@link AgentRuntimeConfig#MAX_MA_NEST_DEPTH}
     * 的下一跳不再装进目标表，执行时按熔断提示跳过，而不是静默当成「目标已停用」。
     * 入口 → 方案 → 专家只占深度 1，不会被这道上限拦住。
     *
     * @param def   要装载的方案
     * @param depth 当前方案相对根的深度
     * @param stack 本链已出现的方案主键，脏数据成环时停住，不把装载跑死
     * @return 执行来源
     * @throws TrialFailure 根方案的判路由模型明确配置了但不可用
     */
    private MaRunSpec buildLevel(MaDef def, int depth, Set<Long> stack) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse(def.getConfigJson(), AgentRunMode.ROUTER.name());
        // 列上的模板是权威值；历史并行类 / 串行类在这里收成两种协作模式，不另写执行分支。
        // 入口保持 ENTRY_ROUTER，不能因为配置 JSON 里残留串行码就被收成路由后串行。
        String shown = AgentRuntimeConfig.isEntryRouterTemplate(def.getTemplate())
                ? AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER
                : AgentRuntimeConfig.canonicalMaTemplate(def.getTemplate());
        runtime.setMaTemplate(shown == null ? AgentRuntimeConfig.TEMPLATE_ROUTER_PARALLEL : shown);
        // 读路径收口：历史串行方案可能仍存着隔离或共享草稿。只改本次内存里的策略再执行，
        // 不抛错、不写库。保存侧仍拒绝这种提交。意图路由分发和入口不进这个分支。
        if (AgentRuntimeConfig.TEMPLATE_ROUTER_THEN_SEQUENCE.equals(runtime.getMaTemplate())
                && !AgentRuntimeConfig.CONTEXT_PRIOR_SUMMARY_ONLY.equals(runtime.getMaContextPolicy())) {
            runtime.setMaContextPolicy(AgentRuntimeConfig.CONTEXT_PRIOR_SUMMARY_ONLY);
        }
        List<MaCandidate> candidates = RoutePoolDedupPolicy.preferSchemes(
                mas.listCandidates(def.getId()).stream()
                        .filter(item -> item.getEnabled() != null && item.getEnabled() == 1)
                        .toList(),
                mas::listCandidates);
        Long judge = resolveJudgeModel(def, runtime);
        Map<Long, MaRunSpec> targets = resolveMaTargets(def, candidates, judge, depth, stack);
        Map<Long, WorkflowRunSpec> workflows = workflowTargets.load(candidates, tenant());
        return MaRunSpec.of(def, candidates, judge, runtime, targets, workflows);
    }

    /**
     * 装载本方案的 MA 候选目标。
     *
     * <p>为什么在这里做完全部校验：执行器每轮都可能在热路径上跑，不能让它边跑边查方案表；
     * 装载失败的目标直接不进目标表，执行器据此把它标成「已跳过」，时间线给中文原因。
     * 深度超过上限的下一跳也不进表，执行侧用熔断文案而不是「已停用」。
     *
     * <p>硬拒绝（不进目标表）：
     * <ul>
     *   <li>目标不存在 / 跨租户 / 已停用；</li>
     *   <li>目标是平台默认接待或对话入口；</li>
     *   <li>本链已经走过这个方案（脏数据成环）；</li>
     *   <li>再往下会超过嵌套上限。</li>
     * </ul>
     *
     * @param def        父方案
     * @param candidates 父方案的启用候选
     * @param judge      父方案的判路由模型；目标方案没配时沿用
     * @param depth      父方案相对根的深度
     * @param stack      本链已出现的方案主键
     * @return 目标主键 → 目标方案执行来源；没有 MA 候选时为空表
     */
    private Map<Long, MaRunSpec> resolveMaTargets(MaDef def, List<MaCandidate> candidates, Long judge,
                                                  int depth, Set<Long> stack) {
        Map<Long, MaRunSpec> targets = new LinkedHashMap<>();
        if (def.getId() != null && !stack.add(def.getId())) {
            return targets;
        }
        try {
            for (MaCandidate item : candidates) {
                if (!item.isMaTarget() || item.getMaDefId() == null) {
                    continue;
                }
                // 自指在写入侧已被拦；这里再兜一层，避免脏数据把一轮执行拖成递归
                if (def.getId() != null && def.getId().equals(item.getMaDefId())) {
                    continue;
                }
                MaDef target = mas.findById(item.getMaDefId()).orElse(null);
                if (target == null || target.getTenantId() == null || target.getTenantId() != tenant()) {
                    continue;
                }
                if (target.getEnabled() == null || target.getEnabled() != 1) {
                    continue;
                }
                // 防循环：任何默认接待都不能被当作路由目标
                if (target.getIsReceptionDefault() != null && target.getIsReceptionDefault() == 1) {
                    continue;
                }
                // 入口类型同样不可作为目标：它是平台唯一网关，只向内派发，不进目标表
                if (AgentRuntimeConfig.isEntryRouterTemplate(target.getTemplate())) {
                    continue;
                }
                if (stack.contains(target.getId())) {
                    continue;
                }
                // 下一跳已经超过上限：不装载，留给执行侧打出熔断文案
                if (depth + 1 > AgentRuntimeConfig.MAX_MA_NEST_DEPTH) {
                    continue;
                }
                try {
                    MaRunSpec nested = buildLevel(target, depth + 1, stack);
                    targets.put(target.getId(), judge == null ? nested : nested.withJudgeModel(judge));
                } catch (TrialFailure ex) {
                    // 目标不可执行（判路由模型非法）：本轮按「已跳过」展示，不影响其它候选。
                    // 历史串行隔离已在 buildLevel 收口，不再把那句拒绝语冒泡成整轮失败。
                    log.warn("目标编排方案装载失败 maId={} targetId={} reason={}",
                            def.getId(), target.getId(), ex.getMessage());
                }
            }
            return targets;
        } finally {
            if (def.getId() != null) {
                stack.remove(def.getId());
            }
        }
    }

    /**
     * 解析判路由 / 汇总模型。
     *
     * <p>字段优先级（写入交付对照表）：
     * <ol>
     *   <li>{@code config_json.maJudgeModelInstanceId}：{@code model_instance.id}，首选；</li>
     *   <li>{@code config_json.maJudgeModel}：纯数字按实例 id；否则按启用中 ROUTER / CHAT
     *       实例的 {@code model_id} 或展示名大小写不敏感匹配；</li>
     *   <li>都没有：返回 null，由执行器回落平台 ROUTER / CHAT 能力槽；
     *       仍无可用模型时在真正需要判路由时给出中文失败。</li>
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

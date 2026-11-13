package com.agenthub.agent.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.domain.service.MaTenantProvider;
import com.agenthub.agent.domain.service.RoutePoolDedupPolicy;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 平台默认接待方案的解析与就绪判定。所属域：agent 的 application。
 *
 * <p>调用方：
 * <ul>
 *   <li>{@code ReceptionConfigAppService}：简易配置页回显当前默认方案与候选；</li>
 *   <li>{@code WorkbenchChatAppService}：工作台 {@code config / sessions} 前先判定就绪；</li>
 *   <li>{@code TrialRunAppService}：工作台虚拟根会话缺判路由模型时回落平台 ROUTER / CHAT 槽。</li>
 * </ul>
 *
 * <p>边界：<b>不自动挑别的方案</b>。默认位为空 / 已停用 / 已删除时一律返回未就绪，
 * 由调用方给出明确报错，避免「悄悄换了一条线」。
 *
 * <p>线程：无状态，可被请求线程直接调用；每次读库，不做进程内缓存。
 */
@Component
public class ReceptionSchemeResolver {

    private final MaDefRepository mas;
    private final ModelInstanceRepository instances;
    private final MaTenantProvider tenantProvider;

    /**
     * @param mas            方案仓储（默认位与候选）
     * @param instances      模型实例，用于解析平台判路由槽
     * @param tenantProvider 当前租户；一期由配置提供
     */
    public ReceptionSchemeResolver(MaDefRepository mas, ModelInstanceRepository instances,
                                   MaTenantProvider tenantProvider) {
        this.mas = mas;
        this.instances = instances;
        this.tenantProvider = tenantProvider;
    }

    /**
     * 当前租户的默认接待快照。
     *
     * @return 方案、有序候选、就绪标记与未就绪原因；方案不存在时 def 为 null
     */
    public Snapshot snapshot() {
        long tenantId = tenantProvider.currentTenantId();
        Optional<MaDef> found = mas.findReceptionDefault(tenantId);
        if (found.isEmpty()) {
            return new Snapshot(null, List.of(), false, "尚未设置默认接待方案，请先执行初始化种子或联系管理员", null);
        }
        MaDef def = found.get();
        List<MaCandidate> candidates = enabledCandidates(def.getId());
        if (def.getEnabled() == null || def.getEnabled() != AgentStatus.ENABLED) {
            return new Snapshot(def, candidates, false, "默认接待方案已停用，请先在多智能体里启用它", null);
        }
        if (candidates.isEmpty()) {
            return new Snapshot(def, candidates, false,
                    "默认接待方案还没有可用的路由目标，请先添加聊天机器人（子 Agent）或编排方案", null);
        }
        // 方案自己配了判路由模型就不再回落平台：执行侧 MaTrialSpecLoader 也按同一优先级读
        Long configured = configuredJudge(def);
        if (configured != null) {
            ModelInstance instance = instances.findById(configured).orElse(null);
            if (instance == null || !usable(instance)) {
                return new Snapshot(def, candidates, false,
                        "默认接待方案配置的判路由模型不存在或已停用，请到模型中心确认", null);
            }
            return new Snapshot(def, candidates, true, null, configured);
        }
        if (hasJudgeAlias(def)) {
            // 别名由执行侧按 model_id / 展示名匹配；这里不重复猜，交给开跑时的明确报错
            return new Snapshot(def, candidates, true, null, null);
        }
        Optional<Long> judge = platformJudgeModel();
        if (judge.isEmpty()) {
            return new Snapshot(def, candidates, false,
                    "没有可用的判路由模型，请到模型中心配置一个启用的 ROUTER 或 CHAT 实例", null);
        }
        return new Snapshot(def, candidates, true, null, judge.get());
    }

    /**
     * 平台判路由槽：ROUTER 优先，其次 CHAT；只认启用且属于对话形态的实例。
     *
     * @return 模型实例 id；都没有为空
     */
    public Optional<Long> platformJudgeModel() {
        for (ModelCapability capability : List.of(ModelCapability.ROUTER, ModelCapability.CHAT)) {
            for (ModelInstance instance : instances.listEnabledByCapability(capability.name())) {
                if (usable(instance)) {
                    return Optional.of(instance.getId());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 本租户已被任一方案占用的候选 Agent 主键。
     *
     * @return Agent 主键集合；用于「添加子 Agent」下拉排除
     */
    public List<Long> usedAgentIds() {
        return mas.listCandidateAgentIds(tenantProvider.currentTenantId());
    }

    /**
     * 方案启用中的候选，已按「方案优先」去重。
     *
     * <p>运行时展开候选也走这条规则：库里若有历史遗留的「方案 + 其成员裸专家」并存，
     * 入口只应该看到那套方案，否则判路由会在两条线路上来回抢同一个 Agent。
     * 去重不影响排序，也不合并两套方案（那是入口 Router 的判定，不是本方法的职责）。
     *
     * @param maId 方案主键
     * @return 启用中的候选，sort_no 小的在前
     */
    public List<MaCandidate> enabledCandidates(Long maId) {
        List<MaCandidate> enabled = mas.listCandidates(maId).stream()
                .filter(item -> item.getEnabled() != null && item.getEnabled() == AgentStatus.ENABLED)
                .toList();
        return RoutePoolDedupPolicy.preferSchemes(enabled, mas::listCandidates);
    }

    /**
     * 方案配置里钉死的判路由模型实例 id。
     *
     * @param def 默认方案
     * @return 实例 id；未配置为空
     */
    private Long configuredJudge(MaDef def) {
        return AgentRuntimeConfig.parse(def.getConfigJson(), AgentRunMode.ROUTER.name())
                .getMaJudgeModelInstanceId();
    }

    /**
     * 方案是否用「模型别名」配了判路由模型。别名匹配由执行侧统一处理，
     * 本类只负责就绪判定，不重复实现一套匹配规则。
     *
     * @param def 默认方案
     * @return true 表示配了非空别名
     */
    private boolean hasJudgeAlias(MaDef def) {
        String alias = AgentRuntimeConfig.parse(def.getConfigJson(), AgentRunMode.ROUTER.name())
                .getMaJudgeModel();
        return alias != null && !alias.isBlank();
    }

    /**
     * 实例可用：存在、启用、用途属于对话形态（CHAT / ROUTER / REWRITE / SUMMARY / MEMORY）。
     * 嵌入等用途不能拿来判路由，必须在解析阶段就排除。
     */
    private boolean usable(ModelInstance instance) {
        if (instance == null || !RecordStatus.isEnabled(instance.getStatus())) {
            return false;
        }
        return ModelCapability.tryParse(instance.getCapability())
                .map(ModelCapability::usesChat)
                .orElse(false);
    }

    /**
     * 默认接待的一次只读快照。
     *
     * @param def                 默认方案；未配置时为 null
     * @param candidates          启用中的候选（有序）
     * @param ready               是否可建会话 / 发消息
     * @param reason              未就绪原因（人话，可直接展示）
     * @param judgeModelInstanceId 实际可用的判路由模型；未就绪为 null
     */
    public record Snapshot(MaDef def, List<MaCandidate> candidates, boolean ready, String reason,
                           Long judgeModelInstanceId) {
    }
}

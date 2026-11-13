package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.ReceptionAgentOptionResp;
import com.agenthub.agent.api.dto.ReceptionCandidateAddReq;
import com.agenthub.agent.api.dto.ReceptionCandidateEditReq;
import com.agenthub.agent.api.dto.ReceptionCandidateResp;
import com.agenthub.agent.api.dto.ReceptionConfigResp;
import com.agenthub.agent.api.dto.ReceptionSchemeOptionResp;
import com.agenthub.agent.api.dto.ReceptionWorkflowOptionResp;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.domain.service.AgentRouteDefaults;
import com.agenthub.agent.domain.service.MaDefPolicy;
import com.agenthub.agent.domain.service.MaRouteKeys;
import com.agenthub.agent.domain.service.MaTenantProvider;
import com.agenthub.agent.domain.service.RoutePoolDedupPolicy;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.text.TextClip;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 默认接待简易配置。所属域：agent 的 application。
 *
 * <p>刀4 起本页收口为「只管理路由池名单」：添加 / 启停 / 排序 / 移除。
 * <ul>
 *   <li>接待人设不再在本页维护：它只配在排序第一的候选 Agent（接待助手）自己的系统提示词里；</li>
 *   <li>意图说明单一真源是 Agent 简介（{@code agent_def.description}），本页只读回显，不写；</li>
 *   <li>判路由 System Prompt 由后端内置组装，页面与方案都不参与。</li>
 * </ul>
 *
 * <p>边界：
 * <ul>
 *   <li>候选只允许现网合法子类型（REACT / PLAN_AND_EXECUTE、启用、允许被编排），且简介必填；</li>
 *   <li>已被任一套多智能体方案列为候选的 Agent 不允许再挂进默认接待，避免一条线被两处拉走；</li>
 *   <li>刀5 / 刀5B：池里还能挂「另一套编排方案」与「已发布工作流」，两者的门闩分别委托
 *       {@link MaDefPolicy#assertMaTargets} / {@link MaDefPolicy#assertWorkflowTargets}；
 *       非 Agent 目标不接管接待人设与寒暄兜底。</li>
 * </ul>
 */
@Service
public class ReceptionConfigAppService {

    /** 单方案候选硬顶，与 MaDefPolicy 保持一致 */
    private static final int MAX_CANDIDATES = MaDefPolicy.MAX_CANDIDATES;
    /** ma_candidate.intent_desc 列宽：简介回填时按此截断 */
    private static final int INTENT_MAX = 512;

    private final MaDefRepository mas;
    private final AgentDefRepository agents;
    private final ReceptionSchemeResolver reception;
    private final MaTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    /** 刀5B：工作流候选的可用性校验与展示信息（只读） */
    private final WorkflowDefRepository workflowDefs;

    /**
     * @param mas             方案与候选
     * @param agents          Agent 定义（读简介、校验子类型）
     * @param reception       默认接待解析与就绪判定
     * @param tenantProvider  当前租户
     * @param operatorContext 当前操作人
     * @param operLogWriter   操作日志（只记动作，不记提示词正文）
     * @param workflowDefs    工作流定义（刀5B：判定已发布 / 说明 / 回显名称）
     */
    public ReceptionConfigAppService(MaDefRepository mas, AgentDefRepository agents,
                                     ReceptionSchemeResolver reception, MaTenantProvider tenantProvider,
                                     OperatorContext operatorContext, OperLogWriter operLogWriter,
                                     WorkflowDefRepository workflowDefs) {
        this.mas = mas;
        this.agents = agents;
        this.reception = reception;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.workflowDefs = workflowDefs;
    }

    /**
     * 回显默认接待配置：方案信息、候选名单与就绪状态。
     *
     * @return 配置视图；未配置默认方案时 schemeId 为空、ready=false
     */
    public ReceptionConfigResp config() {
        ReceptionSchemeResolver.Snapshot snapshot = reception.snapshot();
        ReceptionConfigResp resp = new ReceptionConfigResp();
        resp.setReady(snapshot.ready());
        resp.setReason(snapshot.reason());
        resp.setCandidates(List.of());
        MaDef def = snapshot.def();
        if (def == null) {
            return resp;
        }
        resp.setSchemeId(def.getId());
        resp.setSchemeCode(def.getCode());
        // 入口统一显示「Router」；普通方案仍用库里的名称
        resp.setSchemeName(def.displayName());
        resp.setTemplate(def.getTemplate());
        resp.setEnabled(def.getEnabled());

        List<MaCandidate> candidates = mas.listCandidates(def.getId());
        List<Long> agentIds = candidates.stream().map(MaCandidate::getAgentId).distinct()
                .filter(id -> id != null).toList();
        Map<Long, AgentDef> agentMap = loadAgents(agentIds);
        // 刀5 / 刀5B：MA 与工作流候选要回显目标名 / 编码 / 状态 / 说明，各自一次批量取
        Map<Long, MaDef> schemeMap = loadSchemes(candidates);
        Map<Long, WorkflowDef> workflowMap = loadWorkflows(candidates);
        List<ReceptionCandidateResp> rows = new ArrayList<>(candidates.size());
        // 接待助手必须是 Agent：人设只配在 Agent 的系统提示词里，MA / 工作流候选即使排第一也不承担人设
        MaCandidate assistantCandidate = candidates.stream()
                .filter(item -> !item.isMaTarget() && !item.isWorkflowTarget() && item.getAgentId() != null)
                .findFirst()
                .orElse(null);
        for (MaCandidate candidate : candidates) {
            // 三个 Map 都可能是 Map.of()（不可变），null 键取值为 NPE，按类型只取对应一边
            AgentDef child = candidate.getAgentId() == null ? null : agentMap.get(candidate.getAgentId());
            MaDef target = candidate.getMaDefId() == null ? null : schemeMap.get(candidate.getMaDefId());
            WorkflowDef workflow = candidate.getWorkflowDefId() == null
                    ? null : workflowMap.get(candidate.getWorkflowDefId());
            rows.add(toCandidateResp(candidate, child, target, workflow,
                    assistantCandidate != null && assistantCandidate.getId().equals(candidate.getId())));
        }
        resp.setCandidates(rows);

        // 接待助手＝排序第一的 Agent 候选；页面只读展示它是谁，不再提供提示词编辑
        AgentDef assistant = assistantCandidate == null ? null : agentMap.get(assistantCandidate.getAgentId());
        if (assistant != null) {
            resp.setPromptAgentId(assistant.getId());
            resp.setPromptAgentName(assistant.getName());
        }
        return resp;
    }

    /**
     * 「添加子 Agent」下拉：只出能挂、且没被任何方案占用的 Agent。
     *
     * @return 选项列表；没有可选项时为空
     */
    public List<ReceptionAgentOptionResp> availableAgents() {
        Set<Long> used = new HashSet<>(reception.usedAgentIds());
        MaDef def = reception.snapshot().def();
        List<String> allowedModes = def == null ? List.of(AgentRunMode.REACT.name())
                : runtimeOf(def).childRunModesAllowedOrDefault();
        List<ReceptionAgentOptionResp> rows = new ArrayList<>();
        for (AgentDef agent : agents.listOrchestrable()) {
            if (used.contains(agent.getId()) || !allowedModes.contains(agent.getRunMode())) {
                continue;
            }
            ReceptionAgentOptionResp row = new ReceptionAgentOptionResp();
            row.setId(agent.getId());
            row.setName(agent.getName());
            row.setCode(agent.getCode());
            row.setRunMode(agent.getRunMode());
            row.setDescription(agent.getDescription());
            // 简介为空仍列出（否则运营不知道少了谁），但前端要标出「未填简介、加不进去」
            row.setDescriptionMissing(agent.getDescription() == null || agent.getDescription().isBlank());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 「添加方案」下拉（刀5）：可挂进默认接待路由池的其它多智能体方案。
     *
     * <p>过滤口径与保存侧 {@link MaDefPolicy#assertMaTargets} 完全一致，避免「下拉里能选、
     * 点了却 400」：已启用、同租户、非自己、非任一默认接待、自己没有 MA 候选（只下一层）、
     * 模板会判路由、还没在本池里。方案说明为空仍列出，但前端必须标出且不允许提交。
     *
     * @return 选项列表；默认接待未配置时为空
     */
    public List<ReceptionSchemeOptionResp> availableSchemes() {
        MaDef self = reception.snapshot().def();
        if (self == null) {
            return List.of();
        }
        long tenantId = tenantProvider.currentTenantId();
        // 已在池里的方案不再出现在下拉：候选行（含停用）存在即视为已在池，避免选了才 400
        Set<Long> inPool = mas.listCandidates(self.getId()).stream()
                .filter(MaCandidate::isMaTarget)
                .map(MaCandidate::getMaDefId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        List<ReceptionSchemeOptionResp> rows = new ArrayList<>();
        for (MaDef def : mas.listByTenant(tenantId)) {
            if (def.getId().equals(self.getId()) || inPool.contains(def.getId())) {
                continue;
            }
            if (def.getEnabled() == null || def.getEnabled() != AgentStatus.ENABLED) {
                continue;
            }
            // 防循环：任何默认接待都不能被当成目标
            if (def.getIsReceptionDefault() != null && def.getIsReceptionDefault() == 1) {
                continue;
            }
            // 入口类型同样不能入池：它是平台唯一网关，只向内派发
            if (AgentRuntimeConfig.isEntryRouterTemplate(def.getTemplate())) {
                continue;
            }
            // 历史并行类 / 串行类收成两种模式后都可以入池；认不出的模板仍不列出
            String canonical = AgentRuntimeConfig.canonicalMaTemplate(def.getTemplate());
            if (!AgentRuntimeConfig.TEMPLATE_ROUTER_PARALLEL.equals(canonical)
                    && !AgentRuntimeConfig.TEMPLATE_ROUTER_THEN_SEQUENCE.equals(canonical)) {
                continue;
            }
            ReceptionSchemeOptionResp row = new ReceptionSchemeOptionResp();
            row.setId(def.getId());
            row.setName(def.getName());
            row.setCode(def.getCode());
            row.setTemplate(def.getTemplate());
            row.setDescription(def.getDescription());
            row.setDescriptionMissing(def.getDescription() == null || def.getDescription().isBlank());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 「添加工作流」下拉（刀5B）：可挂进默认接待路由池的已发布工作流。
     *
     * <p>过滤口径与保存侧 {@link MaDefPolicy#assertWorkflowTargets} 完全一致：
     * 未删除、同租户、{@code status=HAS_RELEASE} 且有默认发布指针、还没在本池里。
     * 说明为空 / 无可用发布仍列出，但前端必须标出且不允许提交。
     *
     * @return 选项列表；默认接待未配置时为空
     */
    public List<ReceptionWorkflowOptionResp> availableWorkflows() {
        MaDef self = reception.snapshot().def();
        if (self == null) {
            return List.of();
        }
        long tenantId = tenantProvider.currentTenantId();
        // 已在池里的工作流不再出现在下拉：候选行（含停用）存在即视为已在池，避免选了才 400
        Set<Long> inPool = mas.listCandidates(self.getId()).stream()
                .filter(MaCandidate::isWorkflowTarget)
                .map(MaCandidate::getWorkflowDefId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        List<ReceptionWorkflowOptionResp> rows = new ArrayList<>();
        for (WorkflowDef def : workflowDefs.listRoutable(tenantId, 200)) {
            if (def.getId() == null || inPool.contains(def.getId())) {
                continue;
            }
            ReceptionWorkflowOptionResp row = new ReceptionWorkflowOptionResp();
            row.setId(def.getId());
            row.setName(def.getName());
            row.setCode(def.getCode());
            row.setStatus(def.getStatus());
            row.setDefaultReleaseId(def.getDefaultReleaseId());
            row.setDescription(def.getDescription());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 修改接待提示词：<b>本页已不再维护</b>，一律明确拒绝。
     *
     * <p>刀4 起接待人设只配在接待助手 Agent 自己的系统提示词里（Agent 管理 → 接待助手）。
     * 保留该入口只为让旧前端/旧脚本拿到明确失败，而不是「保存成功但什么也没变」。
     *
     * @param rawPrompt 旧参数；不再读取
     * @return 永不返回
     * @throws BizException 固定提示去 Agent 管理修改
     */
    @Deprecated
    public ReceptionConfigResp updatePrompt(String rawPrompt) {
        throw new BizException(ErrorCode.BAD_REQUEST,
                "接待提示词已不在默认接待页维护：请到 Agent 管理编辑「接待助手」的系统提示词");
    }

    /**
     * 添加一个子 Agent 到默认接待。
     *
     * <p>意图说明取自该 Agent 的简介，因此简介为空直接拒绝：不允许用空描述去判路由。
     *
     * @param req agentId 必填；intentDesc 已废止，传了也忽略
     * @return 更新后的配置
     * @throws BizException 未配置默认方案、Agent 不可挂、简介为空、已被其它方案占用、候选已满
     */
    @Transactional
    public ReceptionConfigResp addCandidate(ReceptionCandidateAddReq req) {
        MaDef def = requireDefault();
        if (req.getMaDefId() != null && req.getAgentId() == null) {
            return addSchemeCandidate(def, req.getMaDefId());
        }
        // 刀5B：工作流候选排在方案之后，三者都为空才报「请选择」
        if (req.getWorkflowDefId() != null && req.getAgentId() == null && req.getMaDefId() == null) {
            return addWorkflowCandidate(def, req.getWorkflowDefId());
        }
        if (req.getAgentId() == null) {
            // 三个都没给：明确 400，而不是把 null 带进仓储去查
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要添加的子 Agent、编排方案或工作流");
        }
        AgentDef agent = agents.findById(req.getAgentId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在或已删除"));
        if (reception.usedAgentIds().contains(agent.getId())) {
            throw new BizException(ErrorCode.DUPLICATE,
                    "「" + displayName(agent) + "」已被某个多智能体方案列为候选，请先从原方案移除再添加");
        }
        // 去重（方案优先）：该 Agent 已作为某套入池方案的成员被覆盖时，不再重复加入裸专家，
        // 否则入口和场景方案会抢同一个人，判路由也容易出现「两条线路指同一专家」
        Optional<Long> covering = RoutePoolDedupPolicy.schemeCovering(agent.getId(),
                mas.listCandidates(def.getId()), mas::listCandidates);
        if (covering.isPresent()) {
            MaDef scheme = mas.findById(covering.get()).orElse(null);
            throw new BizException(ErrorCode.DUPLICATE,
                    "「" + displayName(agent) + "」已包含在路由池的方案「" + schemeDisplayName(scheme)
                            + "」里；入口会先分给那套方案，不能再重复加入裸专家");
        }
        // 意图说明单一真源：没有简介就没法判路由，必须在 Agent 管理先补齐
        String description = TextClip.trimmed(agent.getDescription(), INTENT_MAX);
        if (description == null || description.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "「" + displayName(agent) + "」还没有填写简介；默认接待用 Agent 简介判路由，"
                            + "请先到 Agent 管理补上再添加");
        }
        List<MaCandidate> existing = mas.listCandidates(def.getId());
        MaDefPolicy.assertCandidateCount(existing.size() + 1);

        Set<String> usedKeys = existing.stream()
                .map(MaCandidate::getRouteKey)
                .filter(key -> key != null && !key.isBlank())
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        MaCandidate candidate = new MaCandidate();
        candidate.setMaId(def.getId());
        candidate.setAgentId(agent.getId());
        candidate.setRouteKey(AgentRouteDefaults.resolveKey(agent.getCode(), agent.getId(), null, usedKeys));
        // 用当时的简介回填列：列 NOT NULL，且旧代码仍可能读它
        candidate.setIntentDesc(description);
        candidate.setSortNo(AgentRouteDefaults.nextSort(maxSort(existing)));
        candidate.setEnabled(AgentStatus.ENABLED);
        // 复用现网同一套候选门闩：子类型、启用、allow_orchestrated、父级允许的子运行模式
        MaDefPolicy.assertCandidates(runtimeOf(def), List.of(candidate), agents::findById);

        mas.insertCandidate(candidate);
        operLogWriter.write("agent", "reception_candidate_add", "ma_def", String.valueOf(def.getId()),
                "默认接待添加子 Agent " + agent.getId() + " route=" + candidate.getRouteKey());
        return config();
    }

    /**
     * 把「另一套多智能体方案」加进默认接待路由池（刀5）。
     *
     * <p>门闩全部委托 {@link MaDefPolicy#assertMaTargets}：与多智能体抽屉保存同一套，
     * 保证「默认接待页能加」和「方案自己保存能加」不会出现口径差。
     *
     * @param def      默认接待方案
     * @param targetId 目标方案主键
     * @return 更新后的配置
     * @throws BizException 目标不可作为路由目标 / 已在本池 / 候选已满
     */
    private ReceptionConfigResp addSchemeCandidate(MaDef def, Long targetId) {
        List<MaCandidate> existing = mas.listCandidates(def.getId());
        MaDefPolicy.assertCandidateCount(existing.size() + 1);
        Set<String> usedKeys = existing.stream()
                .map(MaCandidate::getRouteKey)
                .filter(key -> key != null && !key.isBlank())
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        MaDef target = mas.findById(targetId).orElse(null);

        MaCandidate candidate = new MaCandidate();
        candidate.setMaId(def.getId());
        candidate.setTargetType(MaCandidate.TARGET_MA);
        candidate.setMaDefId(targetId);
        candidate.setRouteKey(MaRouteKeys.forTarget(targetId,
                target == null ? null : target.getCode(), usedKeys));
        candidate.setIntentDesc(target == null ? null : target.getDescription());
        candidate.setSortNo(AgentRouteDefaults.nextSort(maxSort(existing)));
        candidate.setEnabled(AgentStatus.ENABLED);

        // 重复加入：先拦，避免被后面的「说明为空」文案盖住真实原因
        boolean duplicated = existing.stream().anyMatch(item -> item.isMaTarget()
                && targetId.equals(item.getMaDefId()));
        if (duplicated) {
            throw new BizException(ErrorCode.DUPLICATE, "该编排方案已在默认接待路由池中");
        }
        // 统一门闩：启用 / 同租户 / 非自己 / 非默认接待 / 叶子 / 有说明 / 模板判路由
        MaDefPolicy.assertMaTargets(List.of(candidate), def.getId(), def.getTemplate(),
                tenantProvider.currentTenantId(), mas::findById, mas::listCandidates, Set.of());

        // 去重（方案优先）：该方案已经包含的裸专家从入口名单移除，避免同一个 Agent 占两条线路。
        // 两套方案之间成员重叠不处理——选哪套交给入口 Router 判。
        Set<Long> memberIds = new HashSet<>();
        for (MaCandidate member : mas.listCandidates(targetId)) {
            if (member.getAgentId() != null) {
                memberIds.add(member.getAgentId());
            }
        }
        List<Long> removedAgentIds = new ArrayList<>();
        for (MaCandidate row : existing) {
            if (row.isMaTarget() || row.isWorkflowTarget() || row.getAgentId() == null) {
                continue;
            }
            if (memberIds.contains(row.getAgentId())
                    && mas.deleteCandidate(row.getId(), def.getId()) > 0) {
                removedAgentIds.add(row.getAgentId());
            }
        }

        mas.insertCandidate(candidate);
        operLogWriter.write("agent", "reception_candidate_add", "ma_def", String.valueOf(def.getId()),
                "默认接待添加编排方案 " + targetId + " route=" + candidate.getRouteKey());
        ReceptionConfigResp resp = config();
        if (!removedAgentIds.isEmpty()) {
            // 明确告知被移除的裸专家：不能静默删掉运营刚看到的线路
            String names = agentNames(removedAgentIds);
            resp.setNotices(List.of("已按「方案优先」移除与「" + schemeDisplayName(target)
                    + "」成员重复的裸专家：" + names));
        }
        return resp;
    }

    /**
     * 把「已发布工作流」加进默认接待路由池（刀5B）。
     *
     * <p>门闩全部委托 {@link MaDefPolicy#assertWorkflowTargets}：与多智能体抽屉保存同一套，
     * 保证「默认接待页能加」和「方案自己保存能加」不会出现口径差。
     *
     * <p>路由键按工作流编码派生（{@code MaRouteKeys.forTarget} 的「编码 → 类型前缀_主键」兜底链），
     * 意图说明列用工作流说明回填（列 NOT NULL，真正判路由仍读 {@code workflow_def.description}）。
     *
     * @param def        默认接待方案
     * @param workflowId 目标工作流主键
     * @return 更新后的配置
     * @throws BizException 目标不可作为路由目标 / 已在本池 / 候选已满
     */
    private ReceptionConfigResp addWorkflowCandidate(MaDef def, Long workflowId) {
        List<MaCandidate> existing = mas.listCandidates(def.getId());
        MaDefPolicy.assertCandidateCount(existing.size() + 1);
        Set<String> usedKeys = existing.stream()
                .map(MaCandidate::getRouteKey)
                .filter(key -> key != null && !key.isBlank())
                .map(key -> key.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        WorkflowDef target = workflowDefs.findById(workflowId).orElse(null);

        MaCandidate candidate = new MaCandidate();
        candidate.setMaId(def.getId());
        candidate.setTargetType(MaCandidate.TARGET_WORKFLOW);
        candidate.setWorkflowDefId(workflowId);
        candidate.setRouteKey(MaRouteKeys.forWorkflow(workflowId,
                target == null ? null : target.getCode(), usedKeys));
        candidate.setIntentDesc(target == null ? null : target.getDescription());
        candidate.setSortNo(AgentRouteDefaults.nextSort(maxSort(existing)));
        candidate.setEnabled(AgentStatus.ENABLED);

        // 重复加入：先拦，避免被后面的「未发布 / 说明为空」文案盖住真实原因
        boolean duplicated = existing.stream().anyMatch(item -> item.isWorkflowTarget()
                && workflowId.equals(item.getWorkflowDefId()));
        if (duplicated) {
            throw new BizException(ErrorCode.DUPLICATE, "该工作流已在默认接待路由池中");
        }
        // 统一门闩：同租户 / 已发布 + 默认发布指针 / 有说明 / 模板判路由
        MaDefPolicy.assertWorkflowTargets(List.of(candidate), def.getTemplate(),
                tenantProvider.currentTenantId(), this::workflowTargetInfo, Set.of());

        mas.insertCandidate(candidate);
        operLogWriter.write("agent", "reception_candidate_add", "ma_def", String.valueOf(def.getId()),
                "默认接待添加工作流 " + workflowId + " route=" + candidate.getRouteKey());
        return config();
    }

    /**
     * 工作流目标的校验快照：只把 agent 域需要的字段翻译过去，不把实体交给策略。
     *
     * @param workflowId workflow_def.id
     * @return 只读快照；不存在为空
     */
    private Optional<MaDefPolicy.WorkflowTargetInfo> workflowTargetInfo(Long workflowId) {
        return workflowDefs.findById(workflowId).map(def -> new MaDefPolicy.WorkflowTargetInfo(
                def.getId(), def.getTenantId(), def.getName(), def.getCode(),
                def.getDescription(), def.getStatus(), def.getDefaultReleaseId()));
    }

    /**
     * 改候选的启停。<b>意图说明不再可改</b>：它只来自 Agent 简介。
     *
     * @param candidateId 候选主键
     * @param req         只读 enabled；带 intentDesc 一律拒绝
     * @return 更新后的配置
     * @throws BizException 未配置默认方案、候选不存在、试图改意图说明
     */
    @Transactional
    public ReceptionConfigResp updateCandidate(Long candidateId, ReceptionCandidateEditReq req) {
        MaDef def = requireDefault();
        requireCandidate(candidateId, def.getId());
        // 明确失败而不是静默忽略：旧前端还在传 intentDesc 时，必须让运营知道文案入口已换
        if (req.getIntentDesc() != null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "意图说明只来自 Agent 简介，不能在默认接待页单独修改；请到 Agent 管理改简介");
        }
        if (req.getEnabled() == null) {
            // 没有任何要改的列：不生成空 SET 的 UPDATE（MySQL 会报语法错），直接回显当前配置
            return config();
        }
        int affected = mas.updateCandidate(candidateId, def.getId(), null, null, req.getEnabled());
        if (affected == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "候选不存在或不属于默认接待");
        }
        operLogWriter.write("agent", "reception_candidate_update", "ma_candidate", String.valueOf(candidateId),
                "更新默认接待候选启停，方案 " + def.getCode());
        return config();
    }

    /**
     * 从默认接待移除一个候选（含接待助手本身；移除后由下一条接替接待提示词）。
     *
     * @param candidateId 候选主键
     * @return 更新后的配置
     * @throws BizException 未配置默认方案或候选不存在
     */
    @Transactional
    public ReceptionConfigResp removeCandidate(Long candidateId) {
        MaDef def = requireDefault();
        int affected = mas.deleteCandidate(candidateId, def.getId());
        if (affected == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "候选不存在或不属于默认接待");
        }
        operLogWriter.write("agent", "reception_candidate_remove", "ma_candidate", String.valueOf(candidateId),
                "移除默认接待候选，方案 " + def.getCode());
        return config();
    }

    /**
     * 按提交顺序重排候选：ids 必须是当前方案全部候选的一个排列。
     *
     * @param ids 候选主键，首位成为接待助手
     * @return 更新后的配置
     * @throws BizException 未配置默认方案、ids 与现有候选不一致
     */
    @Transactional
    public ReceptionConfigResp reorder(List<Long> ids) {
        MaDef def = requireDefault();
        List<MaCandidate> existing = mas.listCandidates(def.getId());
        Set<Long> current = existing.stream().map(MaCandidate::getId).collect(Collectors.toSet());
        Set<Long> submitted = new HashSet<>(ids == null ? List.of() : ids);
        if (submitted.size() != (ids == null ? 0 : ids.size()) || !submitted.equals(current)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "排序列表必须包含默认接待的全部候选，且不能混入其它方案");
        }
        int sort = 1;
        for (Long id : ids) {
            mas.updateCandidate(id, def.getId(), null, sort++, null);
        }
        operLogWriter.write("agent", "reception_candidate_order", "ma_def", String.valueOf(def.getId()),
                "重排默认接待候选，方案 " + def.getCode());
        return config();
    }

    /**
     * 把某个方案设为平台默认接待。
     *
     * <p>唯一索引保证每租户只有一条；设置前先清其它默认位，因此可以安全换默认。
     *
     * @param maId 方案主键
     * @throws BizException 方案不存在、跨租户或已停用
     */
    @Transactional
    public void setDefault(Long maId) {
        MaDef def = mas.findById(maId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "多智能体方案不存在"));
        long tenantId = tenantProvider.currentTenantId();
        if (def.getTenantId() == null || def.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.NOT_FOUND, "多智能体方案不存在");
        }
        if (def.getEnabled() == null || def.getEnabled() != AgentStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能把启用中的方案设为默认接待");
        }
        // 入口是数据锁定类型：默认接待只能是那一条入口行，普通方案不能被「扶正」成第二入口
        if (!AgentRuntimeConfig.isEntryRouterTemplate(def.getTemplate())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "对话入口是平台唯一入口（ENTRY_ROUTER），不能把普通方案设为默认接待；"
                            + "请改入口自己的模型与提示词");
        }
        int affected = mas.updateReceptionDefault(maId, tenantId, true, operatorContext.requireUserId());
        if (affected == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "多智能体方案不存在");
        }
        operLogWriter.write("agent", "reception_default_set", "ma_def", String.valueOf(maId),
                "设为平台默认接待，方案 " + def.getCode());
    }

    /**
     * 取消当前默认接待。取消后工作台 config 返回未就绪，禁止新建会话；
     * <b>不会自动挑另一条方案</b>。本来就没有默认时按幂等成功处理。
     */
    @Transactional
    public void clearDefault() {
        MaDef def = reception.snapshot().def();
        if (def == null) {
            return;
        }
        // 入口锁定：不能摘掉工作台入口，否则首页会直接变成「未就绪」
        if (AgentRuntimeConfig.isEntryRouterTemplate(def.getTemplate())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "对话入口是数据锁定的唯一入口，不能取消默认接待；如需下线请先与管理员确认迁移方案");
        }
        mas.updateReceptionDefault(def.getId(), def.getTenantId(), false, operatorContext.requireUserId());
        operLogWriter.write("agent", "reception_default_clear", "ma_def", String.valueOf(def.getId()),
                "取消平台默认接待，方案 " + def.getCode());
    }

    /** 取默认接待方案；未配置直接给可展示的中文错误。 */
    private MaDef requireDefault() {
        ReceptionSchemeResolver.Snapshot snapshot = reception.snapshot();
        if (snapshot.def() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "尚未设置默认接待方案，请先执行初始化种子或联系管理员");
        }
        return snapshot.def();
    }

    /** 校验候选属于该方案。 */
    private MaCandidate requireCandidate(Long candidateId, Long maId) {
        return mas.listCandidates(maId).stream()
                .filter(item -> item.getId().equals(candidateId))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "候选不存在或不属于默认接待"));
    }

    /** 方案配置：模板以 ma_def.template 为准，避免 config_json 的同名字段漂移。 */
    private AgentRuntimeConfig runtimeOf(MaDef def) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse(def.getConfigJson(), AgentRunMode.ROUTER.name());
        runtime.setMaTemplate(def.getTemplate());
        return runtime;
    }

    /** 批量取 Agent 展示信息，避免候选列表逐行查库。 */
    private Map<Long, AgentDef> loadAgents(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return agents.findByIds(ids).stream()
                .collect(Collectors.toMap(AgentDef::getId, Function.identity(), (left, right) -> left));
    }

    /** 现有候选的最大排序；无候选返回 null，交给 AgentRouteDefaults 取首个值。 */
    private Integer maxSort(List<MaCandidate> candidates) {
        Integer max = null;
        for (MaCandidate candidate : candidates) {
            if (candidate.getSortNo() != null && (max == null || candidate.getSortNo() > max)) {
                max = candidate.getSortNo();
            }
        }
        return max;
    }

    /** 批量取 MA 候选的目标方案；没有 MA 候选时直接返回空表，不查库。 */
    private Map<Long, MaDef> loadSchemes(List<MaCandidate> candidates) {
        List<Long> ids = candidates.stream().filter(MaCandidate::isMaTarget)
                .map(MaCandidate::getMaDefId).filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mas.findByIds(ids).stream()
                .collect(Collectors.toMap(MaDef::getId, Function.identity(), (left, right) -> left));
    }

    /** 批量取工作流候选的目标定义；没有工作流候选时直接返回空表，不查库。 */
    private Map<Long, WorkflowDef> loadWorkflows(List<MaCandidate> candidates) {
        List<Long> ids = candidates.stream().filter(MaCandidate::isWorkflowTarget)
                .map(MaCandidate::getWorkflowDefId).filter(id -> id != null).distinct().toList();
        Map<Long, WorkflowDef> defs = new java.util.HashMap<>();
        for (Long id : ids) {
            workflowDefs.findById(id).ifPresent(def -> defs.put(def.getId(), def));
        }
        return defs;
    }

    /**
     * 候选行 → 视图。意图说明只读：Agent 取简介，MA 取方案说明，工作流取工作流说明。
     *
     * @param candidate 候选
     * @param agent     子 Agent；MA / 工作流候选为 null
     * @param target    目标方案；非 MA 候选为 null
     * @param workflow  目标工作流；非 WORKFLOW 候选为 null
     * @param assistant 是否接待助手（只有 AGENT 候选可能为 true）
     * @return 视图行
     */
    private ReceptionCandidateResp toCandidateResp(MaCandidate candidate, AgentDef agent, MaDef target,
                                                   WorkflowDef workflow, boolean assistant) {
        ReceptionCandidateResp resp = new ReceptionCandidateResp();
        resp.setId(candidate.getId());
        resp.setTargetType(candidate.targetTypeOrDefault());
        resp.setAgentId(candidate.getAgentId());
        resp.setMaDefId(candidate.getMaDefId());
        resp.setWorkflowDefId(candidate.getWorkflowDefId());
        // 意图说明单一真源：Agent 简介 / 方案说明 / 工作流说明，任一改完本页立刻跟新
        String description;
        if (candidate.isWorkflowTarget()) {
            description = workflow == null ? null : TextClip.trimmed(workflow.getDescription(), INTENT_MAX);
        } else if (candidate.isMaTarget()) {
            description = target == null ? null : TextClip.trimmed(target.getDescription(), INTENT_MAX);
        } else {
            description = agent == null ? null : TextClip.trimmed(agent.getDescription(), INTENT_MAX);
        }
        resp.setIntentDesc(description);
        resp.setIntentDescMissing(description == null || description.isBlank());
        resp.setSortNo(candidate.getSortNo());
        resp.setEnabled(candidate.getEnabled());
        resp.setReceptionAssistant(assistant);
        if (agent != null) {
            resp.setAgentName(agent.getName());
            resp.setAgentCode(agent.getCode());
            resp.setAgentRunMode(agent.getRunMode());
        }
        if (target != null) {
            resp.setMaName(target.getName());
            resp.setMaCode(target.getCode());
            resp.setMaTemplate(target.getTemplate());
            resp.setMaStatus(target.getEnabled());
        }
        if (workflow != null) {
            resp.setWorkflowName(workflow.getName());
            resp.setWorkflowCode(workflow.getCode());
            // 「线路是否已失效」：取消发布或默认发布指针被清掉都算不可运行
            resp.setWorkflowHasRelease(hasRoutableRelease(workflow));
        }
        return resp;
    }

    /** 工作流是否仍有可用发布（与下拉过滤口径一致）。 */
    private static boolean hasRoutableRelease(WorkflowDef def) {
        return def != null && "HAS_RELEASE".equals(def.getStatus()) && def.getDefaultReleaseId() != null;
    }

    /** 错误文案里的 Agent 称呼：名称 → 编码 → 主键。 */
    private static String displayName(AgentDef agent) {
        if (agent.getName() != null && !agent.getName().isBlank()) {
            return agent.getName().trim();
        }
        if (agent.getCode() != null && !agent.getCode().isBlank()) {
            return agent.getCode().trim();
        }
        return "#" + agent.getId();
    }

    /** 错误 / 提示文案里的方案称呼：名称 → 编码 → 主键（方案可为空，回退描述为「该方案」）。 */
    private static String schemeDisplayName(MaDef def) {
        if (def == null) {
            return "该方案";
        }
        if (def.getName() != null && !def.getName().isBlank()) {
            return def.getName().trim();
        }
        if (def.getCode() != null && !def.getCode().isBlank()) {
            return def.getCode().trim();
        }
        return "#" + def.getId();
    }

    /**
     * 批量取 Agent 展示名，用于「自动移除重复裸专家」的提示文案。
     *
     * @param ids Agent 主键
     * @return 以「、」连接的名称串；取不到的行回退主键
     */
    private String agentNames(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        Map<Long, AgentDef> map = loadAgents(ids.stream().distinct().toList());
        return ids.stream()
                .map(id -> map.containsKey(id) ? displayName(map.get(id)) : "#" + id)
                .distinct()
                .collect(Collectors.joining("、"));
    }
}

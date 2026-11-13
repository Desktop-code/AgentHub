package com.agenthub.runtime.application;

import com.agenthub.agent.application.ReceptionSchemeResolver;
import com.agenthub.agent.api.dto.AgentAvatarConfigDto;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.api.dto.WorkbenchCandidateResp;
import com.agenthub.runtime.api.dto.WorkbenchChildOptionResp;
import com.agenthub.runtime.api.dto.WorkbenchConfigResp;
import com.agenthub.runtime.api.dto.WorkbenchRouteOptionResp;
import com.agenthub.runtime.api.dto.WorkbenchRouteOptionsResp;
import com.agenthub.runtime.application.router.RouterTexts;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.service.TrialFailure;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工作台正式对话的薄门面。所属域：runtime 的 application。
 *
 * <p>职责：
 * <ul>
 *   <li>会话创建一律按服务端<b>默认接待方案</b>，忽略前端可能传来的 agentId / maId；</li>
 *   <li>对会话做 {@code entry_channel=WORKBENCH} 校验后，再委托既有 Trial 会话 / 运行服务；</li>
 *   <li>就绪信息来自 {@link ReceptionSchemeResolver}，不在本类重复实现模型与候选规则。</li>
 * </ul>
 *
 * <p>边界：不另写一套执行、SSE、事件落库或记忆逻辑。路由池可挂另一套 MA（刀5）与已发布工作流
 * （刀5B），命中后的执行分别委托给方案执行器与工作流域的现网 run 入口；工作流结果仍投影回
 * 本会话时间线。刀3 的沿用 / 改派仍由 {@link RouterTrialExecutor} 的判路由链路实现，
 * 本类只负责「读本会话可选线路」「写沿用线路 + 置一次性强制标记」。
 */
@Service
public class WorkbenchChatAppService {

    private final ReceptionSchemeResolver reception;
    private final MaTrialSpecLoader maSpecs;
    private final TrialSessionAppService sessions;
    private final TrialRunAppService runs;
    private final TrialAccess access;
    private final AgentDefRepository agents;
    /** 刀5：MA 候选的展示名要读方案表（目标方案名 / 说明），只在读视图用 */
    private final MaDefRepository mas;
    /** 刀5B：工作流候选的展示名 / 说明来自装载后的只读快照（config 视图没有会话快照可复用） */
    private final WorkflowRunSpecLoader workflowSpecs;
    /** 手动换线的一次性强制标记：只作用下一轮，消费即删 */
    private final WorkbenchRouteForceRegistry routeForce;

    /**
     * @param reception 默认接待解析与就绪判定
     * @param maSpecs   方案执行来源（建会话前校验方案可用；换线时读本会话候选）
     * @param sessions  既有会话服务（创建、列表、改名、清空、删除、写线路）
     * @param runs      既有运行服务（发消息、计划批准，供控制器直接调用）
     * @param access    会话归属与渠道校验
     * @param agents    候选 Agent 名称回填与启停校验
     * @param mas       目标方案读取（MA 候选展示名 / 说明 / 启停）
     * @param workflowSpecs 工作流目标装载（WORKFLOW 候选展示名 / 说明）
     * @param routeForce 手动换线的一次性强制标记
     */
    public WorkbenchChatAppService(ReceptionSchemeResolver reception, MaTrialSpecLoader maSpecs,
                                   TrialSessionAppService sessions, TrialRunAppService runs,
                                   TrialAccess access, AgentDefRepository agents, MaDefRepository mas,
                                   WorkflowRunSpecLoader workflowSpecs, WorkbenchRouteForceRegistry routeForce) {
        this.reception = reception;
        this.maSpecs = maSpecs;
        this.sessions = sessions;
        this.runs = runs;
        this.access = access;
        this.agents = agents;
        this.mas = mas;
        this.workflowSpecs = workflowSpecs;
        this.routeForce = routeForce;
    }

    /**
     * 工作台就绪信息与候选名单。
     *
     * @return 就绪标记、原因、默认方案与候选
     */
    public WorkbenchConfigResp config() {
        ReceptionSchemeResolver.Snapshot snapshot = reception.snapshot();
        WorkbenchConfigResp resp = new WorkbenchConfigResp();
        resp.setReady(snapshot.ready());
        resp.setReason(snapshot.reason());
        resp.setCandidates(List.of());
        MaDef def = snapshot.def();
        if (def == null) {
            return resp;
        }
        resp.setSchemeId(def.getId());
        resp.setSchemeCode(def.getCode());
        // 入口统一显示「Router」（与编排列表 / 默认接待同口径）
        resp.setSchemeName(def.displayName());
        resp.setTemplate(def.getTemplate());
        Map<Long, AgentDef> agentMap = loadAgents(snapshot.candidates());
        Map<Long, MaDef> schemeMap = loadSchemes(snapshot.candidates());
        // 刀5B：config 视图没有会话快照，按同一套装载门闩把工作流目标翻成只读快照
        Map<Long, WorkflowRunSpec> workflowMap = def.getTenantId() == null
                ? Map.of() : workflowSpecs.load(snapshot.candidates(), def.getTenantId());
        List<WorkbenchCandidateResp> rows = new ArrayList<>(snapshot.candidates().size());
        for (MaCandidate candidate : snapshot.candidates()) {
            // 非 Agent 候选没有 agent_id：两个 Map 都可能是 Map.of()，null 键取值为 NPE，故按类型分支
            AgentDef agent = candidate.getAgentId() == null ? null : agentMap.get(candidate.getAgentId());
            MaDef target = candidate.isMaTarget() && candidate.getMaDefId() != null
                    ? schemeMap.get(candidate.getMaDefId())
                    : null;
            // 工作流候选的名称 / 说明取自装载成功的目标；装载失败时不展示陈旧信息
            WorkflowRunSpec workflow = candidate.isWorkflowTarget()
                    ? workflowMap.get(candidate.getWorkflowDefId())
                    : null;
            WorkbenchCandidateResp row = new WorkbenchCandidateResp();
            row.setTargetType(candidate.targetTypeOrDefault());
            row.setAgentId(candidate.getAgentId());
            row.setMaDefId(candidate.getMaDefId());
            row.setWorkflowDefId(candidate.getWorkflowDefId());
            row.setAgentName(displayName(candidate, agent,
                    target == null ? null : target.getName(),
                    target == null ? null : target.getCode(),
                    workflow == null ? null : workflow.name(),
                    workflow == null ? null : workflow.code()));
            // 形象：Agent 候选读各自 agent_def；MA 候选读目标方案自己的 ma_def（方案不再借根 Agent）
            if (agent != null) {
                row.setAvatarConfig(AgentAvatarConfigDto.fromJson(agent.getAvatarConfigJson()));
            } else if (target != null) {
                row.setAvatarConfig(AgentAvatarConfigDto.fromJson(target.getAvatarConfigJson()));
            }
            // 意图说明单一真源：Agent 读简介，MA 读方案说明，工作流读工作流说明（都不读 ma_candidate.intent_desc）
            row.setIntentDesc(workflow != null
                    ? RouterTexts.descriptionOfText(workflow.description())
                    : (target != null
                            ? RouterTexts.descriptionOf(target)
                            : RouterTexts.descriptionOf(agent)));
            row.setRouteKey(candidate.getRouteKey());
            row.setSortNo(candidate.getSortNo());
            rows.add(row);
        }
        resp.setCandidates(rows);
        return resp;
    }

    /**
     * 新建工作台会话。不接受任何前端指定的 Agent / 方案。
     *
     * @return 新会话（entryChannel=WORKBENCH）
     * @throws BizException 默认接待未配置 / 已停用 / 未就绪
     */
    public SessionResp createSession() {
        ReceptionSchemeResolver.Snapshot snapshot = reception.snapshot();
        if (!snapshot.ready() || snapshot.def() == null) {
            // 明确失败，不自动挑另一条方案：运营改配置期间不该悄悄换线
            throw new BizException(ErrorCode.BAD_REQUEST,
                    snapshot.reason() == null ? "默认接待尚未就绪，暂时不能开始对话" : snapshot.reason());
        }
        MaRunSpec spec;
        try {
            spec = maSpecs.requireForWorkbench(snapshot.def().getId());
        } catch (TrialFailure ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
        return sessions.createForWorkbench(spec);
    }

    /**
     * 发消息（含 SSE）前的就绪校验。
     *
     * <p>已建的会话在默认接待被停用 / 删除后也禁止继续发送：否则会写出 USER 事件再失败，
     * 用户看到半轮脏数据。明确报错，让运营先修默认接待配置。
     *
     * @throws BizException 默认接待未配置 / 已停用 / 无可用判路由模型
     */
    public void assertReceptionReady() {
        ReceptionSchemeResolver.Snapshot snapshot = reception.snapshot();
        if (!snapshot.ready() || snapshot.def() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    snapshot.reason() == null ? "默认接待尚未就绪，暂时不能发送消息" : snapshot.reason());
        }
    }

    /**
     * 当前用户的工作台会话列表。
     *
     * @param current 页码
     * @param size    页大小
     * @return 会话页，只含 WORKBENCH 渠道
     */
    public PageResult<SessionResp> pageSessions(long current, long size) {
        return sessions.pageWorkbench(current, size);
    }

    /**
     * 工作台会话校验。控制器在发消息 / SSE 前调用，避免调试会话从工作台入口继续跑。
     *
     * @param sessionId 会话主键
     * @return 校验通过的会话
     */
    public Long requireSession(Long sessionId) {
        return access.requireWorkbench(sessionId).getId();
    }

    /**
     * @param sessionId 会话主键
     * @return 会话元数据
     */
    public SessionResp get(Long sessionId) {
        access.requireWorkbench(sessionId);
        return sessions.get(sessionId);
    }

    /**
     * 本会话可选择 / 当前沿用的线路（刀3 换线下拉数据源）。
     *
     * <p>候选来自会话快照方案的启用候选，并再挡一层「子 Agent 未停用 / 未删除」：
     * 方案候选表可能还留着已停用的 Agent，直接列出来会让用户选到一个必然失败的线路。
     *
     * @param sessionId 会话主键
     * @return 当前线路与可选候选
     * @throws BizException 会话不属于当前用户 / 不是工作台会话，或会话方案已不可用
     */
    public WorkbenchRouteOptionsResp routes(Long sessionId) {
        RunSession session = access.requireWorkbench(sessionId);
        MaRunSpec spec = requireSessionSpec(session);
        Map<Long, AgentDef> defs = loadAgents(spec.candidates());
        WorkbenchRouteOptionsResp resp = new WorkbenchRouteOptionsResp();
        List<WorkbenchRouteOptionResp> rows = new ArrayList<>();
        for (MaCandidate candidate : spec.candidates()) {
            // MA / 工作流目标只认「本会话快照里装载成功」的那份：目标停用 / 删除 / 取消发布 /
            // 被移出池后不再出现，前端据此把当前 chip 标成「线路已失效」
            if (candidate.isMaTarget()) {
                MaRunSpec target = spec.maTarget(candidate.getMaDefId()).orElse(null);
                if (target != null) {
                    WorkbenchRouteOptionResp row = routeOption(candidate, null, target);
                    // 方案块形象读方案自己的 ma_def.avatar_config；未配置留空，由前端按方案名走 8 套兜底
                    row.setAvatarConfig(rootAvatarOf(target));
                    // 方案内部各子专家的形象：时间线按「方案 → 各子专家」分块时要各用自己的头像
                    row.setChildren(childOptionsOf(target));
                    rows.add(row);
                }
                continue;
            }
            if (candidate.isWorkflowTarget()) {
                WorkflowRunSpec target = spec.workflowTarget(candidate.getWorkflowDefId()).orElse(null);
                if (target != null) {
                    rows.add(routeOption(candidate, target));
                }
                continue;
            }
            AgentDef agent = defs.get(candidate.getAgentId());
            if (!usableCandidate(agent)) {
                continue;
            }
            rows.add(routeOption(candidate, agent, null));
        }
        resp.setCandidates(rows);
        resp.setCurrentRouteKey(session.getStickyRouteKey());
        if (session.getStickyRouteKey() != null) {
            for (WorkbenchRouteOptionResp row : rows) {
                if (row.getRouteKey() != null
                        && row.getRouteKey().trim().equalsIgnoreCase(session.getStickyRouteKey().trim())) {
                    resp.setCurrentRouteName(row.getAgentName());
                    break;
                }
            }
        }
        return resp;
    }

    /**
     * 手动换线（刀3）。所属域：runtime。
     *
     * <p>只做三件事：校验目标仍可用 → 落沿用线路 → 置一次性强制标记。
     * 下一轮判路由会跳过判定直接命中该候选，并在时间线写「手动换线」；
     * 强制标记消费即删，因此不会把会话永久锁死在某条线上。
     *
     * <p>目标已停用 / 已删除：400 明确文案，<b>不改</b>已有沿用线路。
     *
     * @param sessionId 会话主键
     * @param routeKey  目标候选的 route_key（本会话快照方案内）
     * @return 更新后的会话
     * @throws BizException 会话不可用、目标不属于本方案、目标已停用或未获编排授权
     */
    public SessionResp changeRoute(Long sessionId, String routeKey) {
        RunSession session = access.requireWorkbench(sessionId);
        MaRunSpec spec = requireSessionSpec(session);
        String wanted = routeKey == null ? "" : routeKey.trim();
        MaCandidate target = spec.candidates().stream()
                .filter(item -> item.getRouteKey() != null && item.getRouteKey().trim().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "该线路已停用或不属于本会话的接待方案，请重新选择"));
        // 刀5：换到「另一套编排方案」时只校验目标仍可执行，不再要求 allow_orchestrated：
        // 它是方案不是子 Agent，被允许进池本身就等于获编排授权
        if (target.isMaTarget()) {
            MaRunSpec maTarget = spec.maTarget(target.getMaDefId()).orElse(null);
            if (maTarget == null) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "线路「" + displayName(target, null, null, null, null, null) + "」已停用或已被移出路由池，请重新选择");
            }
            routeForce.put(sessionId, target.getRouteKey());
            // MA 目标没有子 Agent：sticky_agent_id 写 null，线路身份完全由 route_key 表达
            return sessions.bindStickyRoute(sessionId, target.getRouteKey(), null);
        }
        // 刀5B：工作流目标同理——只要快照里还装载得到就可以换线；取消发布 / 移出池后 400 且不动 sticky
        if (target.isWorkflowTarget()) {
            WorkflowRunSpec workflowTarget = spec.workflowTarget(target.getWorkflowDefId()).orElse(null);
            if (workflowTarget == null) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "线路「" + displayName(target, null, null, null, null, null)
                                + "」未发布或已被移出路由池，请重新选择");
            }
            routeForce.put(sessionId, target.getRouteKey());
            // 工作流目标同样没有子 Agent：只写 route_key，sticky_agent_id 保持 null
            return sessions.bindStickyRoute(sessionId, target.getRouteKey(), null);
        }
        AgentDef agent = target.getAgentId() == null ? null : agents.findById(target.getAgentId()).orElse(null);
        if (!usableCandidate(agent)) {
            // 文案点名候选，用户才知道该去启用哪个 Agent，而不是只看到「不合法」
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "线路「" + displayName(target, agent, null, null, null, null) + "」的专家已停用或删除，请重新选择");
        }
        if (agent.getAllowOrchestrated() == null || agent.getAllowOrchestrated() != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "线路「" + displayName(target, agent, null, null, null, null) + "」的专家未允许被编排调用，请先到 Agent 配置里开启");
        }
        routeForce.put(sessionId, target.getRouteKey());
        return sessions.bindStickyRoute(sessionId, target.getRouteKey(), target.getAgentId());
    }

    /**
     * 会话快照方案的执行来源。所属域：runtime。
     *
     * <p>换线必须基于「本会话自己的方案」：换默认接待后再从旧会话换线，
     * 也只能在旧方案候选里选，不能悄悄把人带到新方案的专家上。
     *
     * @param session 工作台会话
     * @return 方案来源
     * @throws BizException 方案不存在 / 已停用 / 跨租户
     */
    private MaRunSpec requireSessionSpec(RunSession session) {
        try {
            // 方案会话不回填 agentId；这里无需再按会话查 Agent，resolve 只认 ma_def_id
            MaRunSpec spec = maSpecs.resolve(session, null);
            if (spec == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "该会话没有可用的编排方案，暂时不能换线");
            }
            return spec;
        } catch (TrialFailure ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
    }

    /** 批量取候选 Agent；空候选不查库。 */
    private Map<Long, AgentDef> loadAgents(List<MaCandidate> candidates) {
        List<Long> ids = candidates.stream().map(MaCandidate::getAgentId).filter(id -> id != null).distinct().toList();
        Map<Long, AgentDef> defs = new HashMap<>();
        if (ids.isEmpty()) {
            return defs;
        }
        for (AgentDef agent : agents.findByIds(ids)) {
            defs.put(agent.getId(), agent);
        }
        return defs;
    }

    /** 批量取 MA 候选的目标方案；没有 MA 候选时不查库。 */
    private Map<Long, MaDef> loadSchemes(List<MaCandidate> candidates) {
        List<Long> ids = candidates.stream().filter(MaCandidate::isMaTarget)
                .map(MaCandidate::getMaDefId).filter(id -> id != null).distinct().toList();
        Map<Long, MaDef> defs = new HashMap<>();
        if (ids.isEmpty()) {
            return defs;
        }
        for (MaDef def : mas.findByIds(ids)) {
            defs.put(def.getId(), def);
        }
        return defs;
    }

    /** 候选是否可用：存在、启用。被编排授权在换线时单独校验并给不同文案。 */
    private static boolean usableCandidate(AgentDef agent) {
        return agent != null && agent.getStatus() != null && agent.getStatus() == AgentStatus.ENABLED;
    }

    /**
     * 方案自己的形象：读 {@code ma_def.avatar_config}，与单兵形象彻底分开。
     *
     * <p>未配置（或历史行没有该列值）返回 null；前端对 null 用固定机器人预设兜底，
     * 不为本功能新增上传链路，也不新造一套方案形象协议。不再按 root_agent_id 借 Agent 形象。
     *
     * @param target 目标方案执行来源
     * @return 形象配置；未配置时为空
     */
    private AgentAvatarConfigDto rootAvatarOf(MaRunSpec target) {
        if (target == null) {
            return null;
        }
        return AgentAvatarConfigDto.fromJson(target.avatarConfigJson());
    }

    /**
     * 方案内部的子候选：展示名 + 形象配置，只给思考时间线解析头像用。
     *
     * <p>按目标方案自己的候选池顺序返回（sort_no 小的在前），与判路由实际可选集合一致；
     * 不带 route_key，不进 @ 面板 / 换线下拉。
     *
     * @param target 目标方案执行来源
     * @return 子候选；没有候选时为空列表
     */
    private List<WorkbenchChildOptionResp> childOptionsOf(MaRunSpec target) {
        if (target == null || target.candidates().isEmpty()) {
            return List.of();
        }
        Map<Long, AgentDef> defs = loadAgents(target.candidates());
        List<WorkbenchChildOptionResp> rows = new ArrayList<>();
        for (MaCandidate item : target.candidates()) {
            AgentDef agent = item.getAgentId() == null ? null : defs.get(item.getAgentId());
            // MA 子方案也回显自己的方案形象：时间线里「方案 → 子专家」每块各用自己的头像
            MaDef childScheme = item.getMaDefId() == null ? null
                    : mas.findById(item.getMaDefId()).orElse(null);
            String name = displayName(item, agent, childScheme == null ? null : childScheme.getName(),
                    childScheme == null ? null : childScheme.getCode(), null, null);
            // 认不出名字的候选（自身已被删除）不补进展示表：时间线不编造参与者
            if (name == null || name.isBlank()) {
                continue;
            }
            WorkbenchChildOptionResp row = new WorkbenchChildOptionResp();
            row.setAgentName(name);
            row.setTargetType(item.targetTypeOrDefault());
            if (agent != null) {
                row.setAvatarConfig(AgentAvatarConfigDto.fromJson(agent.getAvatarConfigJson()));
            } else if (childScheme != null) {
                // 子方案的形象读 ma_def 自己；空值由前端走 8 套机器人兜底
                row.setAvatarConfig(AgentAvatarConfigDto.fromJson(childScheme.getAvatarConfigJson()));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 候选 → 下拉项（Agent / MA）。意图说明：Agent 读简介，MA 读方案说明，与配置页同源。 */
    private static WorkbenchRouteOptionResp routeOption(MaCandidate candidate, AgentDef agent, MaRunSpec target) {
        WorkbenchRouteOptionResp row = new WorkbenchRouteOptionResp();
        row.setRouteKey(candidate.getRouteKey());
        row.setTargetType(candidate.targetTypeOrDefault());
        row.setAgentId(candidate.getAgentId());
        row.setMaDefId(candidate.getMaDefId());
        row.setAgentName(displayName(candidate, agent,
                target == null ? null : target.name(),
                target == null ? null : target.code(), null, null));
        if (agent != null) {
            row.setAvatarConfig(AgentAvatarConfigDto.fromJson(agent.getAvatarConfigJson()));
        }
        row.setIntentDesc(target != null
                ? RouterTexts.descriptionOfText(target.description())
                : RouterTexts.descriptionOf(agent));
        row.setSortNo(candidate.getSortNo());
        return row;
    }

    /** 候选 → 下拉项（工作流，刀5B）。意图说明取 {@code workflow_def.description}。 */
    private static WorkbenchRouteOptionResp routeOption(MaCandidate candidate, WorkflowRunSpec target) {
        WorkbenchRouteOptionResp row = new WorkbenchRouteOptionResp();
        row.setRouteKey(candidate.getRouteKey());
        row.setTargetType(candidate.targetTypeOrDefault());
        row.setAgentId(candidate.getAgentId());
        row.setWorkflowDefId(candidate.getWorkflowDefId());
        row.setAgentName(displayName(candidate, null, null, null,
                target == null ? null : target.name(),
                target == null ? null : target.code()));
        row.setIntentDesc(target == null ? null : RouterTexts.descriptionOfText(target.description()));
        row.setSortNo(candidate.getSortNo());
        return row;
    }

    /**
     * 候选展示名：目标名称 → 编码 → Agent 名称 → 编码 → route_key 兜底；不把空串塞给下拉。
     *
     * @param candidate  候选行
     * @param agent      子 Agent；非 Agent 候选传 null
     * @param targetName 目标方案名；非 MA 候选传 null
     * @param targetCode 目标方案编码；非 MA 候选传 null
     * @param workflowName 工作流名称；非 WORKFLOW 候选传 null
     * @param workflowCode 工作流编码；非 WORKFLOW 候选传 null
     * @return 非空展示名
     */
    private static String displayName(MaCandidate candidate, AgentDef agent,
                                      String targetName, String targetCode,
                                      String workflowName, String workflowCode) {
        if (workflowName != null && !workflowName.isBlank()) {
            return workflowName.trim();
        }
        if (workflowCode != null && !workflowCode.isBlank()) {
            return workflowCode.trim();
        }
        if (targetName != null && !targetName.isBlank()) {
            return targetName.trim();
        }
        if (targetCode != null && !targetCode.isBlank()) {
            return targetCode.trim();
        }
        if (agent != null) {
            if (agent.getName() != null && !agent.getName().isBlank()) {
                return agent.getName().trim();
            }
            if (agent.getCode() != null && !agent.getCode().isBlank()) {
                return agent.getCode().trim();
            }
        }
        return candidate.getRouteKey() == null ? "未命名线路" : candidate.getRouteKey().trim();
    }

    /**
     * @param sessionId 会话主键
     * @return 已落库事件
     */
    public List<EventResp> events(Long sessionId) {
        access.requireWorkbench(sessionId);
        return sessions.events(sessionId);
    }

    /**
     * @param sessionId 会话主键
     * @return 进行中一轮的实时快照
     */
    public List<EventResp> liveEvents(Long sessionId) {
        access.requireWorkbench(sessionId);
        return sessions.liveEvents(sessionId);
    }

    /**
     * @param sessionId 会话主键
     * @param title     新标题
     * @return 更新后的会话
     */
    public SessionResp rename(Long sessionId, String title) {
        access.requireWorkbench(sessionId);
        return sessions.rename(sessionId, title);
    }

    /**
     * @param sessionId 会话主键
     * @return 清空后的会话
     */
    public SessionResp clear(Long sessionId) {
        access.requireWorkbench(sessionId);
        // 清空即丢弃未消费的强制线路：旧标记不能挂到清空后的新对话上
        routeForce.clear(sessionId);
        return sessions.clear(sessionId);
    }

    /**
     * @param sessionId 会话主键
     * @return 取消后的会话
     */
    public SessionResp cancel(Long sessionId) {
        access.requireWorkbench(sessionId);
        routeForce.clear(sessionId);
        return sessions.cancel(sessionId);
    }

    /**
     * @param sessionId 会话主键
     */
    public void remove(Long sessionId) {
        access.requireWorkbench(sessionId);
        routeForce.clear(sessionId);
        sessions.remove(sessionId);
    }

    /**
     * @param sessionId 会话主键
     * @return 待批准计划；没有为空
     */
    public Optional<PlanView> pendingPlan(Long sessionId) {
        access.requireWorkbench(sessionId);
        return runs.pendingPlan(sessionId);
    }

    /**
     * 编排根下当前待批的子专家计划（刷新后恢复批准入口）。
     *
     * <p>与单 Agent 计划共用同一条运行链路；候选子 Agent 若开了规划确认，
     * 工作台必须能读回待批状态，否则页面刷新后这一轮会永久卡住。
     *
     * @param sessionId 会话主键
     * @return 子计划待批视图；没有待批子时为空
     */
    public Optional<ChildPlanPendingView> pendingChildPlan(Long sessionId) {
        access.requireWorkbench(sessionId);
        return runs.pendingChildPlan(sessionId);
    }

    /**
     * @param sessionId 会话主键
     * @param routeKey  子路由键，可空
     * @return 批准后的本轮结果
     */
    public MessageResp approvePlan(Long sessionId, String routeKey) {
        access.requireWorkbench(sessionId);
        if (routeKey != null && !routeKey.isBlank()) {
            return runs.approveChildPlan(sessionId, routeKey.trim());
        }
        return runs.approvePlan(sessionId);
    }

    /**
     * @param sessionId 会话主键
     * @param routeKey  子路由键，可空
     * @return 拒绝后的本轮结果
     */
    public MessageResp rejectPlan(Long sessionId, String routeKey) {
        access.requireWorkbench(sessionId);
        if (routeKey != null && !routeKey.isBlank()) {
            return runs.rejectChildPlan(sessionId, routeKey.trim());
        }
        return runs.rejectPlan(sessionId);
    }
}
package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentQuery;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.runtime.api.dto.AgentOptionResp;
import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.api.dto.SessionPageReq;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.model.TitleSource;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.HistoryPageSize;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 试跑会话的创建、查询、取消和删除。所属域：runtime。
 * 删除 RUNNING 前经 closer 批抽 LONG；清空只丢热窗口不抽；取消经 closer 写 CANCELLED 再抽。
 */
@Service
public class TrialSessionAppService {

    private final RunSessionRepository sessions;
    private final RunEventRepository events;
    private final AgentDefRepository agents;
    private final TrialAccess access;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final TrialFlight flight;
    private final TrialProperties properties;
    private final ReActTrialExecutor react;
    private final TrialMemoryCoordinator memory;
    private final TrialSessionCloser closer;
    private final TrialLiveBuffer live;
    private final PlanPendingRegistry pendingPlans;
    private final ChildPlanApprovalRegistry childApprovals;
    private final TrialBindingLoader loader;
    /** 方案读源：虚拟根建会话必须校验方案启用；列表回显方案名 */
    private final MaTrialSpecLoader maSpecs;
    private final MaDefRepository mas;

    /**
     * @param sessions        会话
     * @param events          事件
     * @param agents          只读定义，用来校验启停和回填名称
     * @param access          仅会话所属用户
     * @param operatorContext 当前用户
     * @param operLogWriter   只记动作，不记对话
     * @param flight          与发送共用的单飞
     * @param properties      历史条数上限
     * @param react           清空时丢掉该会话的进程内记忆
     * @param memory          清空时丢热窗口；删除批抽改走 closer，避免挂在本类事务里
     * @param closer          取消结案、删除前批抽、空闲定时批抽
     * @param live            进行中一轮的内存快照，供刷新后的只读轮询
     * @param pendingPlans    待批准计划；清空/取消/删除时一并丢弃，避免批准到已废弃上下文
     * @param childApprovals  编排根下的子计划待批；取消/清空/删除时一并拒绝，避免线程悬挂
     * @param loader          只读模型槽解析，用于判定是否配了 VISION
     * @param maSpecs         方案读源：虚拟根建会话与执行共用同一套方案校验
     * @param mas             只读方案，列表回显 maCode / maName
     */
    public TrialSessionAppService(RunSessionRepository sessions, RunEventRepository events, AgentDefRepository agents,
                                  TrialAccess access, OperatorContext operatorContext, OperLogWriter operLogWriter,
                                  TrialFlight flight, TrialProperties properties, ReActTrialExecutor react,
                                  TrialMemoryCoordinator memory, TrialSessionCloser closer, TrialLiveBuffer live,
                                  PlanPendingRegistry pendingPlans, ChildPlanApprovalRegistry childApprovals,
                                  TrialBindingLoader loader, MaTrialSpecLoader maSpecs, MaDefRepository mas) {
        this.sessions = sessions;
        this.events = events;
        this.agents = agents;
        this.access = access;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.flight = flight;
        this.properties = properties;
        this.react = react;
        this.memory = memory;
        this.closer = closer;
        this.live = live;
        this.pendingPlans = pendingPlans;
        this.childApprovals = childApprovals;
        this.loader = loader;
        this.maSpecs = maSpecs;
        this.mas = mas;
    }

    /**
     * 试跑页下拉。只返回启用中的 Agent，避免再要求 agent:list。
     *
     * <p>顺带带出「是否已绑定可用 VISION / ASR / TTS 模型」：前端据此置灰上传与「说话」按钮、
     * 并决定朗读走模型还是浏览器。逐个 Agent 判定的成本是一次绑定表查询 + 少量实例查询，
     * 100 条上限下可以接受；比起让用户传完图或说完话再被拒，这点开销值得。
     *
     * @return 最多 100 条
     */
    public List<AgentOptionResp> enabledAgents() {
        AgentQuery query = new AgentQuery();
        query.setCurrent(1);
        query.setSize(100);
        query.setStatus(AgentStatus.ENABLED);
        return agents.page(query).getRecords().stream().map(agent -> {
            AgentOptionResp resp = new AgentOptionResp();
            resp.setId(agent.getId());
            resp.setName(agent.getName());
            resp.setCode(agent.getCode());
            resp.setRunMode(agent.getRunMode());
            resp.setVisionEnabled(loader.findVisionModel(agent.getId()).isPresent());
            // 语音两用途各自独立：只绑识别也能听写，只绑合成也能朗读
            resp.setAsrEnabled(loader.findSpeechModel(agent.getId(), ModelCapability.ASR).isPresent());
            resp.setTtsEnabled(loader.findSpeechModel(agent.getId(), ModelCapability.TTS).isPresent());
            return resp;
        }).toList();
    }

    /**
     * 单个 Agent 的多模态 / 语音能力。抽屉直接按 agentId 打开时用它，
     * 不必为了几个布尔值把整个下拉列表拉一遍。
     *
     * @param agentId Agent
     * @return 能力视图
     * @throws BizException Agent 不存在
     */
    public AgentOptionResp capability(Long agentId) {
        AgentDef agent = agents.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在"));
        AgentOptionResp resp = new AgentOptionResp();
        resp.setId(agent.getId());
        resp.setName(agent.getName());
        resp.setCode(agent.getCode());
        resp.setRunMode(agent.getRunMode());
        resp.setVisionEnabled(loader.findVisionModel(agent.getId()).isPresent());
        resp.setAsrEnabled(loader.findSpeechModel(agent.getId(), ModelCapability.ASR).isPresent());
        resp.setTtsEnabled(loader.findSpeechModel(agent.getId(), ModelCapability.TTS).isPresent());
        return resp;
    }

    /**
     * 只返回当前用户在指定 Agent 或方案下的会话。管理员也不放行别人的试跑。
     *
     * <p>真分页：页大小只受全站上限（100）约束，调用方要多少给多少；
     * {@code history-limit} 仅为「未传 size」时的默认页大小。返回体的 total 是全量 COUNT，
     * 用于前端判断还有没有下一页。
     *
     * @param req 必须带 agentId 或 maId（二选一），以及 current / size
     * @return 按开始时间倒序的会话页
     * @throws BizException 两者都没给，或同时给了
     */
    public PageResult<SessionResp> page(SessionPageReq req) {
        boolean byAgent = req.getAgentId() != null;
        boolean byMa = req.getMaId() != null;
        if (!byAgent && !byMa) {
            throw new BizException(ErrorCode.BAD_REQUEST, "agentId 与 maId 至少填一个");
        }
        if (byAgent && byMa) {
            // 两种入口语义不同（绑根 / 虚拟根），混着查会同时命中绑根会话与方案会话
            throw new BizException(ErrorCode.BAD_REQUEST, "agentId 与 maId 只能填一个");
        }
        SessionQuery query = new SessionQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(historySize(req.getSize()));
        query.setAgentId(req.getAgentId());
        query.setMaId(req.getMaId());
        // 过滤键只有当前用户。不按租户，也不因 is_admin 放开。不再懒结案。
        long userId = operatorContext.requireUserId();
        query.setUserId(userId);
        PageResult<RunSession> page = sessions.page(query);
        // 整页只按需读方案名：虚拟根行需要，绑根行不需要
        Map<Long, MaDef> maCache = new HashMap<>();
        // Agent 名一次性批量预取：逐行 findById 在 100 条页上就是 100 次主键查询
        Map<Long, String> agentNames = prefetchAgentNames(page.getRecords());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(item -> toResp(item, maCache, agentNames)).toList());
    }

    /**
     * @param id 会话主键
     * @return 详情
     */
    public SessionResp get(Long id) {
        return toResp(access.require(id));
    }

    /**
     * @param id 会话主键
     * @return 全部事件，seq 升序
     */
    public List<EventResp> events(Long id) {
        access.require(id);
        return events.listBySessionId(id).stream().map(TrialSessionAppService::toEvent).toList();
    }

    /**
     * 进行中一轮的实时快照，供刷新页面后的前端继续「边跑边看」。
     *
     * 与 {@link #events(Long)} 的区别：events 读的是已落库的权威事件，要等整轮结束才有内容；
     * 本方法读内存镜像，能立刻反映思考、工具与正文增量，但只在当前进程有效，
     * 且整轮结束后即为空 —— 调用方拿到空列表时应回退到 events。
     *
     * @param id 会话主键
     * @return seq 升序的快照；没有在跑的一轮时为空列表
     */
    public List<EventResp> liveEvents(Long id) {
        access.require(id);
        return live.snapshot(id);
    }

    /**
     * 快照当前 run_mode。停用的 Agent 不能开跑。
     *
     * @param agentId 定义主键
     * @return 新建的会话，状态 RUNNING
     */
    @Transactional
    public SessionResp create(Long agentId) {
        AgentDef agent = agents.findById(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Agent 不存在"));
        if (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Agent 未启用");
        }
        long userId = operatorContext.requireUserId();
        RunSession session = new RunSession();
        session.setAgentId(agent.getId());
        session.setRunMode(agent.getRunMode());
        session.setUserId(userId);
        session.setStatus(RunStatus.RUNNING.name());
        session.setStartTime(LocalDateTime.now());
        session.setMessageCount(0);
        Long id = sessions.insert(session, userId);
        operLogWriter.write("runtime", "create", "agent_run_session", String.valueOf(id),
                "创建试跑会话 " + id + "，Agent " + agent.getId());
        return toResp(sessions.findById(id).orElseThrow());
    }

    /**
     * 从方案新建试跑会话（虚拟根入口）。绑根方案也允许建，但会话同时记 agentId；
     * 虚拟根保持 agentId 为空，执行时由 {@link MaTrialSpecLoader} 提供模板与候选。
     *
     * @param maId 方案主键
     * @return 新会话，状态 RUNNING
     * @throws BizException 方案不存在 / 跨租户 / 已停用
     */
    @Transactional
    public SessionResp createFromMa(Long maId) {
        MaRunSpec spec = maSpecs.requireForCreate(maId);
        long userId = operatorContext.requireUserId();
        RunSession session = new RunSession();
        // 绑根方案回填 rootAgentId；虚拟根保持为空，列表与详情据此区分入口
        session.setAgentId(spec.rootAgentId());
        session.setMaDefId(spec.maId());
        // 方案执行只有一层编排语义：运行模式固定 ROUTER，不跟随 rootAgent 的其它模式
        session.setRunMode(AgentRunMode.ROUTER.name());
        session.setUserId(userId);
        session.setStatus(RunStatus.RUNNING.name());
        session.setStartTime(LocalDateTime.now());
        session.setMessageCount(0);
        Long id = sessions.insert(session, userId);
        operLogWriter.write("runtime", "create", "agent_run_session", String.valueOf(id),
                "创建方案试跑会话 " + id + "，方案 " + spec.maId());
        return toResp(sessions.findById(id).orElseThrow());
    }

    /**
     * 同步试跑进行中不能取消模型请求，只拒绝并提示等待。
     *
     * @param id 会话主键
     * @return 取消后的会话
     */
    @Transactional
    public SessionResp cancel(Long id) {
        RunSession session = access.require(id);
        if (flight.running(id)) {
            // 含「根停在等待子计划批准」：本轮请求线程仍活着，按根取消纪律不许取消，等用户批准/拒绝
            throw new BizException(ErrorCode.BAD_REQUEST, "本轮正在执行，请等待结束后再取消");
        }
        if (!RunStatus.RUNNING.name().equals(session.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能取消尚未完成的会话");
        }
        // 取消即放弃待批准计划，避免之后还能批到一个已作废的会话上
        pendingPlans.drop(id);
        // 兜底清理可能残留的子计划待批与批准槽（根取消后不得再有线程等在那里）
        childApprovals.rejectAll(id);
        closer.cancel(session, operatorContext.requireUserId());
        operLogWriter.write("runtime", "cancel", "agent_run_session", String.valueOf(id), "取消试跑会话 " + id);
        return toResp(sessions.findById(id).orElseThrow());
    }

    /**
     * 改左栏标题。写 session_title 并把 title_source 置 MANUAL，此后异步自动生成不再覆盖。
     *
     * <p>为什么不再写 last_msg_preview：那是「首轮提问的短预览」，
     * 标题一旦复用同一列，自动生成与用户手改就会互相踩；两列分开后各管各的。
     *
     * @param id    会话主键
     * @param title 用户输入的标题
     * @return 更新后的会话
     */
    @Transactional
    public SessionResp rename(Long id, String title) {
        access.require(id);
        // 手改标题按 session_title 列宽与左栏显示宽度截断；预览列不动
        String cleanTitle = TrialTexts.clip(title, TrialTexts.TITLE_LIMIT);
        if (cleanTitle.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标题不能为空");
        }
        long userId = operatorContext.requireUserId();
        sessions.updateTitle(id, cleanTitle, TitleSource.MANUAL.name(), userId);
        operLogWriter.write("runtime", "rename", "agent_run_session", String.valueOf(id), "重命名试跑会话 " + id);
        return toResp(sessions.findById(id).orElseThrow());
    }

    /**
     * 清空当前会话的事件并重置轮次，保留会话和标题，状态回到 RUNNING，之后可以继续发送。
     * 不用 cancel：cancel 会把会话标成 CANCELLED，现有发送接口会拒绝再聊。
     *
     * @param id 会话主键
     * @return 清空后的会话
     */
    @Transactional
    public SessionResp clear(Long id) {
        RunSession session = access.require(id);
        if (flight.running(id)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "本轮正在执行，请等待结束后再清空");
        }
        events.deleteBySessionId(id);
        memory.invalidate(id);
        // 清空即丢弃待批准计划：它引用的用户原文和窗口上下文都已不存在
        pendingPlans.drop(id);
        // 编排根下若有子计划待批，一并拒绝，避免等待线程悬挂在已清空的会话上
        childApprovals.rejectAll(id);
        if (session.getUserId() != null) {
            react.forget(session.getUserId(), id);
        }
        long userId = operatorContext.requireUserId();
        sessions.resetMessages(id, userId);
        operLogWriter.write("runtime", "clear", "agent_run_session", String.valueOf(id), "清空试跑会话消息 " + id);
        return toResp(sessions.findById(id).orElseThrow());
    }

    /**
     * 逻辑删除。进行中的一轮不能删。
     * RUNNING 删除前先经 closer 走与 long_extract_on_session_end 同一套批抽，再清窗口并删行。
     *
     * @param id 会话主键
     */
    @Transactional
    public void remove(Long id) {
        RunSession session = access.require(id);
        if (flight.running(id)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "本轮正在执行，请等待结束后再删除");
        }
        // closer 为 NOT_SUPPORTED：挂起本方法事务后再抽，与空闲/取消同一套 onSessionEnd
        if (RunStatus.RUNNING.name().equals(session.getStatus())) {
            closer.extractBeforeDelete(session);
        }
        memory.invalidate(id);
        pendingPlans.drop(id);
        childApprovals.rejectAll(id);
        sessions.logicDelete(id);
        operLogWriter.write("runtime", "remove", "agent_run_session", String.valueOf(id), "删除试跑会话 " + id);
    }

    private SessionResp toResp(RunSession session) {
        return toResp(session, new HashMap<>(), new HashMap<>());
    }

    /**
     * 一次批量取回本页用到的 Agent 名。
     *
     * <p>为什么必须批量：页大小已从 20 放开到最多 100，逐行 {@code findById} 就是最多 100 次
     * 主键查询；这里按去重后的 id 集合取一次，缺失（已删 / 不存在）的 id 显式补空串，
     * 让 {@link #toResp} 不再回落到逐行查询。
     *
     * @param rows 当前页会话
     * @return agentId → 名称（值不为 null）
     */
    private Map<Long, String> prefetchAgentNames(List<RunSession> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (RunSession row : rows) {
            if (row.getAgentId() != null) {
                ids.add(row.getAgentId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        for (AgentDef def : agents.findByIds(ids)) {
            names.put(def.getId(), def.getName() == null ? "" : def.getName());
        }
        // 软删 / 不存在的 Agent 也要占位，否则 toResp 会为这些行再查一次
        for (Long id : ids) {
            names.putIfAbsent(id, "");
        }
        return names;
    }

    /**
     * 会话 → 接口对象。虚拟根没有 Agent：展示名退回方案名，避免左栏出现空白标题。
     *
     * @param session    领域会话
     * @param maCache    本次请求内的方案缓存；避免整页 N 次主键查询
     * @param agentNames 本次请求内批量预取的 Agent 名；命中即用，未命中才回落单查
     * @return 接口对象
     */
    private SessionResp toResp(RunSession session, Map<Long, MaDef> maCache, Map<Long, String> agentNames) {
        SessionResp resp = new SessionResp();
        resp.setId(session.getId());
        resp.setAgentId(session.getAgentId());
        resp.setMaId(session.getMaDefId());
        String agentName = "";
        if (session.getAgentId() != null) {
            String cached = agentNames.get(session.getAgentId());
            // 页内预取命中；只有单条详情（get）没走预取时才回落到主键查询
            agentName = cached != null ? cached
                    : agents.findById(session.getAgentId()).map(AgentDef::getName).orElse("");
        }
        MaDef ma = session.getMaDefId() == null ? null
                : maCache.computeIfAbsent(session.getMaDefId(), key -> mas.findById(key).orElse(null));
        if (ma != null) {
            resp.setMaCode(ma.getCode());
            resp.setMaName(ma.getName());
        }
        // 虚拟根：Agent 名留空，由方案名兜底；绑根仍展示 Agent 名（方案名另列）
        resp.setAgentName(agentName.isBlank() && ma != null ? ma.getName() : agentName);
        resp.setRunMode(session.getRunMode());
        resp.setUserId(session.getUserId());
        resp.setStatus(session.getStatus());
        resp.setStartTime(session.getStartTime());
        resp.setEndTime(session.getEndTime());
        resp.setMessageCount(session.getMessageCount());
        resp.setSessionTitle(session.getSessionTitle());
        resp.setTitleSource(session.getTitleSource());
        resp.setLastMsgPreview(session.getLastMsgPreview());
        resp.setErrorSummary(session.getErrorSummary());
        resp.setUpdateTime(session.getUpdateTime());
        return resp;
    }

    /**
     * @param event 领域事件
     * @return 接口事件
     */
    public static EventResp toEvent(RunEvent event) {
        EventResp resp = new EventResp();
        resp.setId(event.getId());
        resp.setSessionId(event.getSessionId());
        resp.setSeq(event.getSeq());
        resp.setEventType(event.getEventType());
        resp.setSummary(event.getSummary());
        resp.setContentText(event.getContentText());
        resp.setTokenPrompt(event.getTokenPrompt());
        resp.setTokenCompletion(event.getTokenCompletion());
        resp.setCreateTime(event.getCreateTime());
        return resp;
    }

    /**
     * 解析页大小：语义是「页大小」，不是「总量上限」。
     *
     * <p>旧实现把任意请求都裁到 {@code history-limit}（默认 20），导致左栏永远只有 20 条；
     * 现在交给 {@link HistoryPageSize}：传了 size 就按 size（仍受全站 100 约束），
     * 没传才用配置的默认值。想改「一次看多少」应改前端页大小，而不是在这里缩总量。
     *
     * @param requested 请求的每页条数
     * @return 实际查询条数，落在 [1, 100]
     */
    private long historySize(long requested) {
        return HistoryPageSize.resolve(requested, properties.getHistoryLimit());
    }
}

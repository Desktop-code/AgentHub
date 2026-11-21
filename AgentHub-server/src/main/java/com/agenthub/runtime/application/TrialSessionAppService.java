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
import com.agenthub.runtime.domain.model.EntryChannel;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.model.TitleSource;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.HistoryPageSize;
import com.agenthub.runtime.domain.service.SessionCancelFace;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.domain.service.TurnStillOpen;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import com.agenthub.workflow.application.WorkflowRunAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    private static final Logger log = LoggerFactory.getLogger(TrialSessionAppService.class);

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
     * 工作流取消端：停止工作台一轮时，除中断 ReAct 外还要停掉同会话正在跑的工作流实例。
     * 反向依赖不存在（workflow 不依赖本类），不会形成循环。
     */
    private final WorkflowRunAppService workflowRuns;
    /**
     * 本轮 trace 收口：停止工作台一轮时把仍为 RUNNING 的 trace / 步骤收成 CANCELLED，
     * 避免右上角明细里残留「执行中」。只对工作台入口调用，不改管理端试跑 / 多智能体的口径。
     */
    private final TrialObsHooks obs;

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
     * @param workflowRuns    工作流取消端：停止一轮时连带取消同会话正在跑的 run
     * @param obs             trace 收口：停止工作台一轮时把残留 RUNNING 的 trace / 步骤收成 CANCELLED
     */
    public TrialSessionAppService(RunSessionRepository sessions, RunEventRepository events, AgentDefRepository agents,
                                  TrialAccess access, OperatorContext operatorContext, OperLogWriter operLogWriter,
                                  TrialFlight flight, TrialProperties properties, ReActTrialExecutor react,
                                  TrialMemoryCoordinator memory, TrialSessionCloser closer, TrialLiveBuffer live,
                                  PlanPendingRegistry pendingPlans, ChildPlanApprovalRegistry childApprovals,
                                  TrialBindingLoader loader, MaTrialSpecLoader maSpecs, MaDefRepository mas,
                                  WorkflowRunAppService workflowRuns, TrialObsHooks obs) {
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
        this.workflowRuns = workflowRuns;
        this.obs = obs;
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
        // 调试左栏只查调试会话：工作台正式对话走独立渠道，两套列表互不串
        query.setEntryChannel(EntryChannel.DEBUG.name());
        // 过滤键只有当前用户。不按租户，也不因 is_admin 放开。不再懒结案。
        long userId = operatorContext.requireUserId();
        query.setUserId(userId);
        PageResult<RunSession> page = sessions.page(query);
        // 整页只按需读方案名：虚拟根行需要，绑根行不需要
        Map<Long, MaDef> maCache = new HashMap<>();
        // Agent 名一次性批量预取：逐行 findById 在 100 条页上就是 100 次主键查询
        Map<Long, String> agentNames = prefetchAgentNames(page.getRecords());
        // 取消行是否还有没写完的一轮：整页一次读，避免逐行把助手正文拉出来
        Map<Long, Boolean> openTurns = openTurnsOf(page.getRecords());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(item -> toResp(item, maCache, agentNames, openTurns)).toList());
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
     * 从方案新建试跑会话（方案页「去调试」）。方案以自己为根：
     * 会话只记 {@code ma_def_id}，不再回填任何 Agent 根，历史根值也不会带进新会话。
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
        // 方案就是根：agentId 恒为空，执行时由 MaTrialSpecLoader 提供模板与候选
        session.setAgentId(null);
        session.setMaDefId(spec.maId());
        // 方案执行只有一层编排语义：运行模式固定 ROUTER
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
     * 工作台默认接待建会话。
     *
     * <p>与 {@link #createFromMa(Long)} 的差别：
     * <ul>
     *   <li>不接收前端传入的 agentId / maId，一律用服务端解析出的默认接待方案；</li>
     *   <li>落 {@code entry_channel = WORKBENCH} 与 {@code reception_ma_id}，与调试列表隔离；</li>
     *   <li>方案以自己为根：会话只记 maId，不回填任何 Agent 根。</li>
     * </ul>
     *
     * @param spec 默认接待方案解析结果（非空）
     * @return 新会话，状态 RUNNING
     */
    @Transactional
    public SessionResp createForWorkbench(MaRunSpec spec) {
        long userId = operatorContext.requireUserId();
        RunSession session = new RunSession();
        session.setAgentId(null);
        session.setMaDefId(spec.maId());
        session.setEntryChannel(EntryChannel.WORKBENCH.name());
        session.setReceptionMaId(spec.maId());
        // 方案执行恒为一层编排语义：运行模式固定 ROUTER
        session.setRunMode(AgentRunMode.ROUTER.name());
        session.setUserId(userId);
        session.setStatus(RunStatus.RUNNING.name());
        session.setStartTime(LocalDateTime.now());
        session.setMessageCount(0);
        Long id = sessions.insert(session, userId);
        operLogWriter.write("runtime", "workbench_create", "agent_run_session", String.valueOf(id),
                "工作台新建会话 " + id + "，默认接待方案 " + spec.maId());
        return toResp(sessions.findById(id).orElseThrow());
    }

    /**
     * 工作台左栏：当前用户全部正式对话会话，按开始时间倒序。
     *
     * <p>不按 agentId / maId 过滤：换默认方案后历史会话仍要能看到；
     * 渠道过滤与用户隔离由仓储层完成。
     *
     * @param current 页码
     * @param size    页大小；受全站上限约束
     * @return 会话页
     */
    public PageResult<SessionResp> pageWorkbench(long current, long size) {
        SessionQuery query = new SessionQuery();
        query.setCurrent(current);
        query.setSize(historySize(size));
        query.setEntryChannel(EntryChannel.WORKBENCH.name());
        // 工作台分组读 update_time；列表也按它排，清空后「刚刚」才会真正落到今天第一条
        query.setOrderByActivity(true);
        query.setUserId(operatorContext.requireUserId());
        PageResult<RunSession> page = sessions.page(query);
        Map<Long, MaDef> maCache = new HashMap<>();
        Map<Long, String> agentNames = prefetchAgentNames(page.getRecords());
        Map<Long, Boolean> openTurns = openTurnsOf(page.getRecords());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(item -> toResp(item, maCache, agentNames, openTurns)).toList());
    }

    /**
     * 这一轮还在执行时，走中断：停掉模型和工具，会话保持可继续发送。
     * 没在执行的 RUNNING 会话仍按原取消入口收成 CANCELLED。
     *
     * @param id 会话主键
     * @return 中断或取消后的会话
     */
    @Transactional
    public SessionResp cancel(Long id) {
        RunSession session = access.require(id);
        if (flight.running(id)) {
            /*
             * 先按会话取消工作流实例，再中断 ReAct：
             * 工作流是同步引擎，只有库里变 CANCELLED 才会在下一节点前退出；
             * 只中断 ReAct 会让工作流卡保持「运行中」、节点继续往下跑。
             * 用独立短事务提交，避免引擎线程在本次大事务提交前读不到取消状态。
             * 放在 interrupt 之前：只有工作流子（没有 ReAct 线程登记）的一轮也能真正停下。
             */
            int stoppedRuns = 0;
            RuntimeException workflowFailure = null;
            try {
                stoppedRuns = workflowRuns.cancelActiveByWorkbenchSession(id);
            } catch (RuntimeException ex) {
                // 先记下失败但不在这里抛：宿主线程的模型 / 工具必须先停，否则停一次卡一次
                workflowFailure = ex;
                log.warn("停止时取消工作流失败 sessionId={} type={} cause={}",
                        id, ex.getClass().getSimpleName(), ex.getMessage());
            }
            // 含「根停在等待子计划批准」：请求线程还在，但没有登记成可中断的模型/工具循环时仍拒绝；
            // 已经确认停掉工作流子时按「停整轮」处理，不再要求必须命中 ReAct 登记
            boolean interrupted = react.interrupt(id);
            if (!interrupted && stoppedRuns == 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "本轮正在执行，请等待结束后再取消");
            }
            if (workflowFailure != null) {
                // 工作流没收掉：如实说明，不把这一轮标成已停后又不封口。
                // 只有真的命中了 ReAct 中继时才说「模型已中断」，纯工作流子的一轮不冒充
                String cause = workflowFailure.getMessage() == null
                        ? workflowFailure.getClass().getSimpleName() : workflowFailure.getMessage();
                String prefix = interrupted ? "模型已中断，但工作流取消失败：" : "工作流取消失败：";
                throw new BizException(ErrorCode.BAD_REQUEST, prefix + cause);
            }
            /*
             * 把内存快照封成中断终态：执行线程可能还要一会儿才退出（工作流节点收尾 / 其它并行子），
             * 不封口的话刷新时仍会把最后一张 RUNNING 快照画成运行中、并重新进入续订。
             * 快照里已出现的过程与工作流进度保留，只把进行态改成中断终态。
             *
             * 只对工作台正式对话封口：管理端试跑 / 多智能体运行共用同一套取消与快照，
             * 本刀不改变那些入口的现有回放口径；它们仍走原来的「中断写 error」收口。
             */
            if (EntryChannel.WORKBENCH.name().equals(session.getEntryChannel())) {
                // 先把 trace / 步骤收成 CANCELLED：明细里残留的 RUNNING 会让用户以为还在执行。
                // 执行线程稍后收口时 finishIfRunning 不会把已取消的 trace 再改回其它终态。
                try {
                    obs.cancel(id);
                } catch (RuntimeException ex) {
                    // 埋点是旁路：收不回也不能把一次已经生效的停止改成失败
                    log.warn("停止时取消 trace 失败 sessionId={} type={}",
                            id, ex.getClass().getSimpleName());
                }
                live.sealCancelled(id);
            }
            operLogWriter.write("runtime", "cancel", "agent_run_session", String.valueOf(id),
                    "中断运行 " + id + (stoppedRuns > 0 ? "，已取消工作流 " + stoppedRuns + " 个" : ""));
            return toResp(sessions.findById(id).orElseThrow());
        }
        if (!RunStatus.RUNNING.name().equals(session.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能取消尚未完成的会话");
        }
        // 请求线程已不在（重启 / 异常收尾）也要把仍在跑的工作流实例收掉，避免节点继续执行；
        // 工作流取消失败不挡会话收口，否则会留下一个永远「运行中」的会话
        try {
            workflowRuns.cancelActiveByWorkbenchSession(id);
        } catch (RuntimeException ex) {
            log.warn("取消会话时连带取消工作流失败 sessionId={} type={} cause={}",
                    id, ex.getClass().getSimpleName(), ex.getMessage());
        }
        /*
         * 请求线程已不在时也要把工作台这一轮的内存快照封成终态：
         * 执行线程可能已经不登记 ReAct，但快照里最后一张工作流卡仍是 RUNNING；
         * 刷新后会先按运行中出站。trace / 步骤由 TrialSessionCloser.cancel 统一收口，
         * 这里只补工作台快照，保持其它入口口径不变。
         */
        if (EntryChannel.WORKBENCH.name().equals(session.getEntryChannel())) {
            live.sealCancelled(id);
        }
        // 取消即放弃待批准计划，避免之后还能批到一个已作废的会话上
        pendingPlans.drop(id);
        // 兜底清理可能残留的子计划待批与批准槽（根取消后不得再有线程等在那里）
        childApprovals.rejectAll(id);
        closer.cancel(session, operatorContext.requireUserId());
        operLogWriter.write("runtime", "cancel", "agent_run_session", String.valueOf(id), "取消运行会话 " + id);
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
     * 写会话的线路粘性（刀3）。只由工作台门面在渠道校验通过后调用：
     * 调试会话不该因为工作台接口而改变执行线路。
     *
     * @param id       会话主键
     * @param routeKey 沿用的 route_key；null 表示清空
     * @param agentId  该线路的子 Agent；清空时传 null
     * @return 更新后的会话
     */
    @Transactional
    public SessionResp bindStickyRoute(Long id, String routeKey, Long agentId) {
        access.require(id);
        long userId = operatorContext.requireUserId();
        sessions.updateStickyRoute(id, routeKey, agentId, userId);
        operLogWriter.write("runtime", "workbench_route", "agent_run_session", String.valueOf(id),
                "工作台会话 " + id + (routeKey == null ? " 清除线路沿用" : " 手动换线"));
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

    /**
     * 取消行里哪些还有没写完的一轮。用户点取消的摘要已经能判定，不再读事件。
     * 其它状态不用查：展示就用库里的 status。
     *
     * @param rows 本页或单条会话
     * @return 会话 id → 最后一轮是否还没写完；读失败的行不在表里，按已写完展示
     */
    private Map<Long, Boolean> openTurnsOf(List<RunSession> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>();
        for (RunSession row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            if (!RunStatus.CANCELLED.name().equals(row.getStatus()) || SessionCancelFace.userCancelled(row)) {
                continue;
            }
            ids.add(row.getId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<RunEvent>> grouped = new HashMap<>();
        for (RunEvent mark : events.listClosureMarks(ids)) {
            if (mark == null || mark.getSessionId() == null) {
                continue;
            }
            grouped.computeIfAbsent(mark.getSessionId(), key -> new ArrayList<>()).add(mark);
        }
        Map<Long, Boolean> open = new HashMap<>();
        for (Long id : ids) {
            open.put(id, TurnStillOpen.of(grouped.get(id)));
        }
        return open;
    }

    private SessionResp toResp(RunSession session) {
        List<RunSession> one = session == null ? List.of() : List.of(session);
        return toResp(session, new HashMap<>(), new HashMap<>(), openTurnsOf(one));
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
     * @param openTurns  取消行里最后一轮是否还没写完；没有的 id 按已写完
     * @return 接口对象
     */
    private SessionResp toResp(RunSession session, Map<Long, MaDef> maCache, Map<Long, String> agentNames,
                               Map<Long, Boolean> openTurns) {
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
        // 方案会话没有 Agent：Agent 名留空，由方案名兜底；旧 Agent 试跑仍展示 Agent 名
        resp.setAgentName(agentName.isBlank() && ma != null ? ma.getName() : agentName);
        resp.setRunMode(session.getRunMode());
        resp.setUserId(session.getUserId());
        // 已经写完却被标成取消的历史行，展示回进行中。不改库列。
        boolean turnOpen = session.getId() != null && openTurns != null
                && Boolean.TRUE.equals(openTurns.get(session.getId()));
        resp.setStatus(SessionCancelFace.statusForClient(session, turnOpen));
        resp.setStartTime(session.getStartTime());
        resp.setEndTime(session.getEndTime());
        resp.setMessageCount(session.getMessageCount());
        resp.setSessionTitle(session.getSessionTitle());
        resp.setTitleSource(session.getTitleSource());
        resp.setLastMsgPreview(session.getLastMsgPreview());
        resp.setErrorSummary(session.getErrorSummary());
        resp.setUpdateTime(session.getUpdateTime());
        resp.setEntryChannel(session.getEntryChannel());
        resp.setReceptionMaId(session.getReceptionMaId());
        resp.setStickyRouteKey(session.getStickyRouteKey());
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

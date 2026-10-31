package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentQuery;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.api.dto.AgentOptionResp;
import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.api.dto.SessionPageReq;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
     */
    public TrialSessionAppService(RunSessionRepository sessions, RunEventRepository events, AgentDefRepository agents,
                                  TrialAccess access, OperatorContext operatorContext, OperLogWriter operLogWriter,
                                  TrialFlight flight, TrialProperties properties, ReActTrialExecutor react,
                                  TrialMemoryCoordinator memory, TrialSessionCloser closer, TrialLiveBuffer live,
                                  PlanPendingRegistry pendingPlans) {
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
    }

    /**
     * 试跑页下拉。只返回启用中的 Agent，避免再要求 agent:list。
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
            return resp;
        }).toList();
    }

    /**
     * 只返回当前用户在指定 Agent 下的会话。管理员也不放行别人的试跑。
     *
     * @param req 必须带 agentId
     * @return 按开始时间倒序的会话页
     * @throws BizException 没有 agentId
     */
    public PageResult<SessionResp> page(SessionPageReq req) {
        if (req.getAgentId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "agentId 不能为空");
        }
        SessionQuery query = new SessionQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(historySize(req.getSize()));
        query.setAgentId(req.getAgentId());
        // 过滤键只有当前用户。不按租户，也不因 is_admin 放开。不再懒结案。
        long userId = operatorContext.requireUserId();
        query.setUserId(userId);
        PageResult<RunSession> page = sessions.page(query);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toResp).toList());
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
     * 同步试跑进行中不能取消模型请求，只拒绝并提示等待。
     *
     * @param id 会话主键
     * @return 取消后的会话
     */
    @Transactional
    public SessionResp cancel(Long id) {
        RunSession session = access.require(id);
        if (flight.running(id)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "本轮正在执行，请等待结束后再取消");
        }
        if (!RunStatus.RUNNING.name().equals(session.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能取消尚未完成的会话");
        }
        // 取消即放弃待批准计划，避免之后还能批到一个已作废的会话上
        pendingPlans.drop(id);
        closer.cancel(session, operatorContext.requireUserId());
        operLogWriter.write("runtime", "cancel", "agent_run_session", String.valueOf(id), "取消试跑会话 " + id);
        return toResp(sessions.findById(id).orElseThrow());
    }

    /**
     * 改左栏标题。只写 last_msg_preview，不新增列。
     *
     * @param id    会话主键
     * @param title 用户输入的标题
     * @return 更新后的会话
     */
    @Transactional
    public SessionResp rename(Long id, String title) {
        access.require(id);
        String preview = TrialTexts.preview(title);
        if (preview.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标题不能为空");
        }
        long userId = operatorContext.requireUserId();
        sessions.updateTitle(id, preview, userId);
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
        sessions.logicDelete(id);
        operLogWriter.write("runtime", "remove", "agent_run_session", String.valueOf(id), "删除试跑会话 " + id);
    }

    private SessionResp toResp(RunSession session) {
        SessionResp resp = new SessionResp();
        resp.setId(session.getId());
        resp.setAgentId(session.getAgentId());
        resp.setAgentName(agents.findById(session.getAgentId()).map(AgentDef::getName).orElse(""));
        resp.setRunMode(session.getRunMode());
        resp.setUserId(session.getUserId());
        resp.setStatus(session.getStatus());
        resp.setStartTime(session.getStartTime());
        resp.setEndTime(session.getEndTime());
        resp.setMessageCount(session.getMessageCount());
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
     * 条数不超过 history-limit。调用方传更大的 size 也裁回配置值，默认 20。
     *
     * @param requested 请求的每页条数
     * @return 实际查询条数
     */
    private long historySize(long requested) {
        int cap = properties.getHistoryLimit() > 0 ? properties.getHistoryLimit() : 20;
        long size = requested > 0 ? requested : cap;
        return Math.min(size, cap);
    }
}

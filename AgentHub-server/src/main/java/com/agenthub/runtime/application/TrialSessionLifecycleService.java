package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.domain.model.EntryChannel;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.TitleSource;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 运行会话的生命周期：新建、改名、线路粘性、清空、删除。所属域：runtime。
 *
 * <p>本类不负责取消（{@code cancel} 留在会话门面，涉及工作流 / ReAct / 快照封口等多端协同），
 * 也不负责分页与回放查询（见 {@link TrialSessionQueryService}）。
 * 删除 RUNNING 前经 closer 批抽 LONG；清空只丢热窗口不抽。
 */
@Service
public class TrialSessionLifecycleService {

    private final RunSessionRepository sessions;
    private final RunEventRepository events;
    private final AgentDefRepository agents;
    private final TrialAccess access;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final TrialFlight flight;
    private final ReActTrialExecutor react;
    private final TrialMemoryCoordinator memory;
    private final TrialSessionCloser closer;
    private final PlanPendingRegistry pendingPlans;
    private final ChildPlanApprovalRegistry childApprovals;
    /** 方案读源：虚拟根建会话必须校验方案启用 */
    private final MaTrialSpecLoader maSpecs;
    /** 写回后的接口映射（方案名回显、取消行状态修正）与查询共用同一份口径 */
    private final TrialSessionQueryService query;

    /**
     * @param sessions        会话
     * @param events          清空时丢事件
     * @param agents          只读定义，用来校验启停并回填会话运行模式
     * @param access          仅会话所属用户
     * @param operatorContext 当前用户
     * @param operLogWriter   只记动作，不记对话
     * @param flight          与发送共用的单飞：进行中的一轮不能改名以外的破坏性操作
     * @param react           清空时丢掉该会话的进程内记忆
     * @param memory          清空 / 删除时丢热窗口；删除批抽改走 closer，避免挂在本类事务里
     * @param closer          删除前批抽
     * @param pendingPlans    待批准计划；清空 / 删除时一并丢弃，避免批准到已废弃上下文
     * @param childApprovals  编排根下的子计划待批；清空 / 删除时一并拒绝，避免线程悬挂
     * @param maSpecs         方案读源：虚拟根建会话与执行共用同一套方案校验
     * @param query           会话 → 接口对象
     */
    public TrialSessionLifecycleService(RunSessionRepository sessions, RunEventRepository events,
                                        AgentDefRepository agents, TrialAccess access,
                                        OperatorContext operatorContext, OperLogWriter operLogWriter,
                                        TrialFlight flight, ReActTrialExecutor react,
                                        TrialMemoryCoordinator memory, TrialSessionCloser closer,
                                        PlanPendingRegistry pendingPlans, ChildPlanApprovalRegistry childApprovals,
                                        MaTrialSpecLoader maSpecs, TrialSessionQueryService query) {
        this.sessions = sessions;
        this.events = events;
        this.agents = agents;
        this.access = access;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.flight = flight;
        this.react = react;
        this.memory = memory;
        this.closer = closer;
        this.pendingPlans = pendingPlans;
        this.childApprovals = childApprovals;
        this.maSpecs = maSpecs;
        this.query = query;
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
        return query.toResp(sessions.findById(id).orElseThrow());
    }

    /**
     * 从方案新建运行会话（方案页「去调试」）。方案以自己为根：
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
        return query.toResp(sessions.findById(id).orElseThrow());
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
        return query.toResp(sessions.findById(id).orElseThrow());
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
        return query.toResp(sessions.findById(id).orElseThrow());
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
        return query.toResp(sessions.findById(id).orElseThrow());
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
        return query.toResp(sessions.findById(id).orElseThrow());
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
     * 供门面在取消 / 中断分支里回读最新会话（取消语义本身不搬入本类）。
     *
     * @param id 会话主键
     * @return 接口对象
     */
    SessionResp respOf(Long id) {
        return query.toResp(sessions.findById(id).orElseThrow());
    }
}

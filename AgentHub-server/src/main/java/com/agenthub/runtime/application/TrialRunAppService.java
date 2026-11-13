package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.application.ReceptionSchemeResolver;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.EntryChannel;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RouteHint;
import com.agenthub.runtime.domain.model.RouteSticky;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.TrialIdemGuard;
import com.agenthub.runtime.domain.model.TitleSource;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.PlanJson;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import com.agenthub.runtime.domain.service.SessionTitleTexts;
import com.agenthub.runtime.domain.service.MeterTurnHolder;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialSendGate;
import com.agenthub.runtime.domain.service.TrialTurnOutcome;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.domain.service.VisionOcrTexts;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.VisionOcrExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 发送一轮试跑。所属域：runtime。模型调用在事务外，成功或失败后再落事件。
 * 会话在多轮期间保持 RUNNING，不写结束时间。业务失败写 error 事件后仍保持 RUNNING，方便续聊。
 * 只有一轮没走到成功或失败收尾就逃出时，才经 {@link StaleRunCloser} 收成取消。
 * 操作日志只记会话，不记用户原文、助手正文和工具实参。
 */
@Service
public class TrialRunAppService {

    private static final Logger log = LoggerFactory.getLogger(TrialRunAppService.class);

    private final RunSessionRepository sessions;
    private final RunEventRepository events;
    private final AgentDefRepository agents;
    private final TrialAccess access;
    private final TrialBindingLoader loader;
    private final ReActTrialExecutor react;
    private final RouterTrialExecutor router;
    private final PlanTrialExecutor planRunner;
    private final PlanPendingRegistry pendingPlans;
    private final ChildPlanApprovalRegistry childApprovals;
    private final TrialEventWriter writer;
    /** 本轮结果摊平成时间线事件（计划帧 / Router 分段 / 思考与工具 / 文件 / 助手答复） */
    private final TrialTimelineWriter timeline;
    private final TrialFlight flight;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final TrialObsHooks obs;
    private final TrialMemoryCoordinator memory;
    private final TrialLiveBuffer live;
    private final VisionOcrExecutor vision;
    private final SessionTitleGeneratorAppService titleGenerator;
    /** 方案读源：虚拟根与绑根都按 ma_def 执行；为空时才回落 Agent 旧配置 */
    private final MaTrialSpecLoader maSpecs;
    /** 发消息幂等：同会话 + 同 Idempotency-Key 只真正执行一次 */
    private final TrialMessageIdempotency idempotency;
    /** 默认接待判路由模型回落：仅工作台虚拟根会话使用，调试会话语义不变 */
    private final ReceptionSchemeResolver reception;
    /** 工作台手动换线的一次性强制标记；调试路径从不读取 */
    private final WorkbenchRouteForceRegistry routeForce;
    /** 一轮没能正常收尾时，把仍是 RUNNING 且没被占用的会话收成取消 */
    private final StaleRunCloser staleRuns;

    /**
     * @param sessions        会话
     * @param events          序号
     * @param agents          定义
     * @param access          仅会话所属用户
     * @param loader          ReAct 装配
     * @param react           官方 ReAct
     * @param router          一层分发
     * @param planRunner      计划执行外层编排
     * @param pendingPlans    待批准计划的内存登记表
     * @param childApprovals  编排根下「子计划待批准」槽与登记（同一根同时仅 1 个）
     * @param writer          摘要入库
     * @param flight          单飞
     * @param operatorContext 当前用户
     * @param operLogWriter   动作日志
     * @param obs             调用追踪。失败不影响本轮试跑
     * @param memory          窗口、记忆注入和显式记住
     * @param live            进行中一轮的内存快照，供刷新后的只读轮询
     * @param vision          图片文字提取；无图时完全不参与
     * @param titleGenerator  首轮结束后异步生成会话标题；失败不影响本轮
     * @param maSpecs         方案读源；虚拟根没有 Agent 时由它提供模板、候选与判路由模型
     * @param idempotency     发消息幂等闸门；无键请求不改变原行为
     * @param routeForce      工作台手动换线的一次性强制标记（进程内，只作用一轮）
     * @param staleRuns      异常逃出时收口遗留 RUNNING；正常成功 / 业务失败不走这里
     */
    public TrialRunAppService(RunSessionRepository sessions, RunEventRepository events, AgentDefRepository agents,
                              TrialAccess access, TrialBindingLoader loader, ReActTrialExecutor react,
                              RouterTrialExecutor router, PlanTrialExecutor planRunner,
                              PlanPendingRegistry pendingPlans, ChildPlanApprovalRegistry childApprovals,
                              TrialEventWriter writer, TrialFlight flight,
                              OperatorContext operatorContext, OperLogWriter operLogWriter, TrialObsHooks obs,
                              TrialMemoryCoordinator memory, TrialLiveBuffer live, VisionOcrExecutor vision,
                              SessionTitleGeneratorAppService titleGenerator, MaTrialSpecLoader maSpecs,
                              TrialMessageIdempotency idempotency, ReceptionSchemeResolver reception,
                              WorkbenchRouteForceRegistry routeForce, StaleRunCloser staleRuns) {
        this.sessions = sessions;
        this.events = events;
        this.agents = agents;
        this.access = access;
        this.loader = loader;
        this.react = react;
        this.router = router;
        this.planRunner = planRunner;
        this.pendingPlans = pendingPlans;
        this.childApprovals = childApprovals;
        this.writer = writer;
        this.timeline = new TrialTimelineWriter(writer);
        this.flight = flight;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.obs = obs;
        this.memory = memory;
        this.live = live;
        this.vision = vision;
        this.titleGenerator = titleGenerator;
        this.maSpecs = maSpecs;
        this.idempotency = idempotency;
        this.reception = reception;
        this.routeForce = routeForce;
        this.staleRuns = staleRuns;
    }

    /**
     * 记录带 cause 的 {@link TrialFailure}。
     *
     * <p>为什么不一律当「预期内失败」静默：{@code TrialFailure} 既可能是「撞限」「未绑模型」这类
     * 正常业务拒绝，也可能是上游真正炸了才被包成人话。前者不需要栈，后者必须能查。
     * 因此只在<b>挂了 cause</b> 时打 WARN 全栈，纯业务拒绝保持安静。
     *
     * @param label   日志用标签，如「试跑失败」
     * @param scope   定位用 id（会话 / Agent）
     * @param failure 已映射的失败
     * @param message 已脱敏的人话说明，只用于日志前缀
     */
    private void logTrialFailure(String label, Object scope, TrialFailure failure, String message) {
        if (failure == null || failure.getCause() == null) {
            return;
        }
        log.warn("{} id={} type={} msg={} cause={}", label, scope, failure.getClass().getSimpleName(),
                message, CauseDigest.of(failure.getCause()), failure);
    }

    /**
     * 同步跑完一轮。业务失败也返回 status=FAILED，便于页面展示 error_summary。
     *
     * @param sessionId 会话
     * @param content   用户输入，已通过校验
     * @return 本轮新事件和助手预览
     * @throws BizException 会话已取消，或上一轮还没结束。历史 SUCCESS 不挡。
     */
    public MessageResp send(Long sessionId, String content) {
        return executeTurn(sessionId, content, null, null, null);
    }

    /**
     * 同步跑完一轮（可带图片）。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片 data URI 或 http(s) 地址，可空
     * @return 本轮新事件和助手预览
     * @throws BizException 会话已取消、上一轮未结束，或图片缺少视觉模型
     */
    public MessageResp send(Long sessionId, String content, List<String> images) {
        return executeTurn(sessionId, content, images, null, null);
    }

    /**
     * 同步跑完一轮（带幂等键）。
     *
     * <p>幂等语义见 {@link TrialMessageIdempotency}：同会话 + 同键只真正执行一次，
     * 重复请求返回首次结果的等价响应（{@code replayed=true}，不带事件）。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片，可空
     * @param idemKey   客户端幂等键，可空；空＝保持现网直发行为
     * @return 本轮新事件和助手预览，或重放结果
     * @throws BizException 会话已取消、上一轮未结束、键冲突，或首轮仍在执行
     */
    public MessageResp send(Long sessionId, String content, List<String> images, String idemKey) {
        return executeTurn(sessionId, content, images, null, idemKey);
    }

    /**
     * 边跑边推增量，结束后仍按同步路径落库。失败时写 error 事件，不把堆栈推给页面。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param progress  增量出口，不可空
     * @param end       结束出口，不可空
     */
    public void stream(Long sessionId, String content, TrialProgress progress, TrialStreamEnd end) {
        stream(sessionId, content, null, progress, end);
    }

    /**
     * 边跑边推增量（可带图片）。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片 data URI 或 http(s) 地址，可空
     * @param progress  增量出口，不可空
     * @param end       结束出口，不可空
     */
    public void stream(Long sessionId, String content, List<String> images,
                       TrialProgress progress, TrialStreamEnd end) {
        stream(sessionId, content, images, progress, end, null);
    }

    /**
     * 边跑边推增量（可带图片 + 幂等键）。
     *
     * <p>SSE 重连 / 客户端重试带同一个键时不会重跑：命中终态直接补一帧 done
     * （重放响应不带正文增量），前端收尾后按 sessionId 回拉事件即可看到完整一轮。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片，可空
     * @param progress  增量出口，不可空
     * @param end       结束出口，不可空
     * @param idemKey   客户端幂等键，可空
     */
    public void stream(Long sessionId, String content, List<String> images,
                       TrialProgress progress, TrialStreamEnd end, String idemKey) {
        try {
            progress.phase("planning");
            end.done(executeTurn(sessionId, content, images, progress, idemKey));
        } catch (BizException ex) {
            end.fail(ex.getMessage());
        } catch (TrialFailure ex) {
            // 这里极少走到（executeTurn 内部已把 TrialFailure 收成 FAILED 响应）；
            // 万一走到，文案保持与原来一致，只在带 cause 时补栈
            logTrialFailure("试跑流失败（已映射）", sessionId, ex, ex.getMessage());
            end.fail("试跑失败");
        } catch (RuntimeException ex) {
            // 流式路径的兜底 catch：带栈打，否则 SSE 断了以后控制台只剩一行类名
            log.warn("试跑流失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            end.fail("试跑失败");
        }
    }

    /**
     * 批准待执行的计划：接着把已落库的那份计划串行执行完并汇总。
     *
     * <p>用户原文与已注入记忆的用户侧文本都来自登记项，不重新拼一遍——
     * 重拼会把窗口滚动摘要推进两次，同一轮被计两次摘要。
     *
     * @param sessionId 会话（必须是当前用户自己的，且确实停在待批准）
     * @return 本轮新事件与终答预览
     * @throws BizException 没有待批准计划、计划已过期，或该会话正在执行另一轮
     */
    public MessageResp approvePlan(Long sessionId) {
        RunSession session = access.require(sessionId);
        TrialSendGate.assertSendable(session);
        long userId = operatorContext.requireUserId();
        PendingPlan pending = pendingPlans.take(sessionId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有待批准的计划（可能已过期，请重新提问）"));
        if (!flight.tryStart(sessionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该会话正在执行");
        }
        List<RunEvent> written = new ArrayList<>();
        ObsTraceScope trace = null;
        // 只有没走到 succeed / fail 就逃出时才收口；业务失败仍保持 RUNNING
        boolean settled = false;
        try {
            trace = obs.begin(session, pending.userText());
            AgentDef agent = session.getAgentId() == null ? null
                    : agents.findById(session.getAgentId())
                    .orElseThrow(() -> new TrialFailure("Agent 不存在"));
            if (agent == null) {
                // 虚拟根是 ROUTER，本不该产生单 Agent 待批计划；兜底拒绝而不是 NPE
                throw new TrialFailure("该会话没有 Agent，无法续跑计划；请重新提问");
            }
            if (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED) {
                throw new TrialFailure("Agent 未启用");
            }
            int seq = events.nextSeq(sessionId);
            // 批准不是新的用户输入：不再落 USER 事件，直接续执行并推进后续序号。
            // 同时把这一轮镜像进内存快照（从这条 seq 起），页面才有办法在执行中途看到
            // 「第 N 步进行中」——否则批准后要一直干等接口返回，界面上只剩一个转圈按钮。
            live.beginAt(sessionId, seq);
            PlanProgressRecorder recorder = new PlanProgressRecorder(live.recorder(sessionId));
            TrialTurn turn = executePendingPlan(agent, pending, session, written, seq, recorder);
            obs.finishOk(trace);
            // 同一轮的续跑：不重复累加 message_count
            MessageResp done = succeed(session, turn, written, pending.userText(), false);
            settled = true;
            return done;
        } catch (TrialFailure ex) {
            logTrialFailure("批准计划后试跑失败", sessionId, ex, ex.getMessage());
            // 有 cause 时把脱敏摘要提到 trace 级：面板第一行就能看到真正原因
            obs.finishError(trace, ex.getMessage(), ex.getFinishReason(), ex.getCause());
            MessageResp failed = fail(session, written, ex.getMessage(), pending.userText(), false);
            settled = true;
            return failed;
        } catch (RuntimeException ex) {
            // 批准续跑失败：带栈 + 脱敏 cause，便于区分「模型出站失败」与「装配缺失」
            log.warn("批准计划后执行失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            obs.finishError(trace, "试跑失败", null, ex);
            MessageResp failed = fail(session, written, "试跑失败", pending.userText(), false);
            settled = true;
            return failed;
        } finally {
            PromptInjectionHolder.clear();
            obs.clear();
            // 本轮结束：内存快照完成使命，权威数据是刚落库的事件
            live.clear(sessionId);
            flight.finish(sessionId);
            // fail() 自己抛错时错误事件可能没写上，会话会停在 RUNNING，刷新后左栏一直转
            if (!settled) {
                staleRuns.abandonIfIdle(sessionId);
            }
        }
    }

    /**
     * 取消待执行的计划：落一条 REJECTED 计划事件，本轮就此收束。
     * 没有任何步骤被执行，因此不写 assistant、不动记忆窗口。
     *
     * @param sessionId 会话
     * @return 本轮新事件（只有一条计划状态事件）
     * @throws BizException 没有待批准计划
     */
    public MessageResp rejectPlan(Long sessionId) {
        RunSession session = access.require(sessionId);
        long userId = operatorContext.requireUserId();
        PendingPlan pending = pendingPlans.take(sessionId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有待批准的计划（可能已过期）"));
        List<RunEvent> written = new ArrayList<>();
        int seq = events.nextSeq(sessionId);
        PlanView rejected = new PlanView(pending.planId(), PlanState.REJECTED, List.of());
        written.add(writer.append(sessionId, seq, EventType.PLAN, "计划已取消",
                PlanJson.planPayload(rejected), null, null));
        // 同一轮收束：用户轮次已在发问时计过，这里不再累加
        recordTurn(session, "", "", false);
        operLogWriter.write("runtime", "run", "agent_run_session", String.valueOf(sessionId),
                "试跑会话 " + sessionId + " 计划已取消");
        MessageResp resp = response(session, "计划已取消", written);
        resp.setStatus(RunStatus.SUCCESS.name());
        return resp;
    }

    /**
     * 查询该会话是否有待批准计划。前端刷新后靠它恢复「批准 / 取消」按钮。
     *
     * @param sessionId 会话
     * @return 有待批准计划时为计划视图（PENDING）；否则为空
     */
    public Optional<PlanView> pendingPlan(Long sessionId) {
        access.require(sessionId);
        long userId = operatorContext.requireUserId();
        // 登记项自带归属用户：即便会话校验通过，也不把别人的计划内容回显出来
        return pendingPlans.peek(sessionId)
                .filter(item -> item.userId() == userId)
                .map(item -> new PlanView(item.planId(), PlanState.PENDING, item.steps()));
    }

    /**
     * 编排根下的子计划待批准：批准并唤醒等待中的子线程。
     *
     * <p>与单 Agent 的 {@link #approvePlan(Long)} 分开：这条路径不续跑任何根侧步骤，
     * 只是把控制权还给「已规划未执行」的 P&E 子线程；根终答仍由 Router 等齐后统一汇总。
     *
     * @param sessionId 根会话
     * @param routeKey  待批子的路由键；空表示当前唯一待批
     * @return 轻量确认（无新事件，根 SSE 会继续推后续增量）
     * @throws BizException 没有待批子计划、已失效，或不属于当前用户
     */
    public MessageResp approveChildPlan(Long sessionId, String routeKey) {
        return decideChildPlan(sessionId, routeKey, true);
    }

    /**
     * 编排根下的子计划待批准：拒绝。该子以失败占位继续，其它子不受影响。
     *
     * @param sessionId 根会话
     * @param routeKey  待批子的路由键；空表示当前唯一待批
     * @return 轻量确认
     * @throws BizException 没有待批子计划、已失效，或不属于当前用户
     */
    public MessageResp rejectChildPlan(Long sessionId, String routeKey) {
        return decideChildPlan(sessionId, routeKey, false);
    }

    /**
     * 查当前待批的子计划。刷新页面后前端靠它恢复「批准 / 拒绝」按钮；
     * 与根 SSE 的 {@code ma_child_plan_pending} 载荷同构。
     *
     * @param sessionId 根会话
     * @return 有待批子时为其视图；否则为空
     */
    public Optional<ChildPlanPendingView> pendingChildPlan(Long sessionId) {
        access.require(sessionId);
        long userId = operatorContext.requireUserId();
        return childApprovals.peek(sessionId)
                .filter(item -> item.plan().userId() == userId)
                .map(item -> new ChildPlanPendingView(item.routeKey(), item.childAgentId(), item.childAgentName(),
                        item.plan().planId(), PlanState.PENDING.name(), item.plan().steps(),
                        ChildPlanPendingView.RUN_STATE_PAUSED));
    }

    /**
     * 批准 / 拒绝子计划的共用落库与校验。
     *
     * @param sessionId 根会话
     * @param routeKey  路由键，可空
     * @param approved  true＝批准，false＝拒绝
     * @return 轻量确认
     */
    private MessageResp decideChildPlan(Long sessionId, String routeKey, boolean approved) {
        access.require(sessionId);
        long userId = operatorContext.requireUserId();
        ChildPlanApprovalRegistry.PendingChildPlan pending = childApprovals.find(sessionId, routeKey)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "没有待批准的子计划（可能已过期或已被处理）"));
        // 归属校验：会话校验只证明「库里的会话是你的」，这里再核登记项自身的 userId
        if (pending.plan().userId() != userId) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权处理其它用户的子计划");
        }
        boolean decided = approved
                ? childApprovals.approve(sessionId, routeKey)
                : childApprovals.reject(sessionId, routeKey);
        if (!decided) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该子计划已失效（可能已超时或被取消），请重新提问");
        }
        operLogWriter.write("runtime", approved ? "childPlanApprove" : "childPlanReject", "agent_run_session",
                String.valueOf(sessionId),
                "子专家「" + pending.childAgentName() + "」计划" + (approved ? "已批准" : "已拒绝"));
        MessageResp resp = new MessageResp();
        resp.setSessionId(sessionId);
        resp.setStatus(RunStatus.SUCCESS.name());
        resp.setPlanPending(false);
        resp.setChildPlanApproved(approved);
        resp.setChildRouteKey(pending.routeKey());
        resp.setAssistantPreview(approved ? "已批准子计划，继续执行" : "已拒绝子计划，该子已跳过");
        return resp;
    }

    /**
     * 一轮发消息的外层闸门：归属校验 → 幂等占位 → 执行 → 回写终态。
     *
     * <p>幂等放在这里而不是 Controller：SSE 路径与同步路径共用同一处判定，
     * 且「占位 → 执行 → 留终态」必须紧贴真正跑模型的那一段，中间不能插入别的写库动作。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片，可空
     * @param progress  增量出口，可空（同步 send）
     * @param idemKey   客户端幂等键，可空
     * @return 本轮结果，或命中重放时的等价结果
     * @throws BizException 会话不可发、幂等键冲突，或首轮仍在执行
     */
    private MessageResp executeTurn(Long sessionId, String content, List<String> images, TrialProgress progress,
                                    String idemKey) {
        RunSession session = access.require(sessionId);
        // 仅取消（及已删除由 access 挡）不可再发。历史 SUCCESS 不挡；空闲不再写成 SUCCESS。
        TrialSendGate.assertSendable(session);
        // 幂等闸门放在归属校验之后：别人的会话不该在幂等表里留下占位行
        TrialIdemGuard guard = idempotency.begin(sessionId, idemKey, content, images);
        if (guard.hasReplay()) {
            // 同键同内容已跑完：不插消息、不调模型、不写流水，直接返回等价结果
            return guard.replay();
        }
        // 本轮幂等键进计量上下文：同一轮若被重复执行，账本会拿到同一批 dedupe_key
        MeterTurnHolder.begin(guard.managed() ? guard.idemKey() : null);
        try {
            MessageResp resp = runTurn(session, content, images, progress);
            // 成功与业务失败都是结论，必须落终态；否则同键重试会被判成「进行中」
            idempotency.complete(guard, resp);
            return resp;
        } catch (RuntimeException ex) {
            // 未形成结论就抛出（会话正忙、图片非法等）：释放占位，允许同键重试
            idempotency.release(guard);
            throw ex;
        } finally {
            // 线程池复用：必须清掉本轮 turnKey，避免污染下一轮账本去重键
            MeterTurnHolder.clear();
        }
    }

    /**
     * 真正跑一轮：单飞 → 落用户事件 → 按运行模式分派 → 落助手与收尾。
     *
     * @param session  已校验归属的会话（幂等闸门已通过）
     * @param content  用户输入
     * @param images   图片，可空
     * @param progress 增量出口，可空
     * @return 本轮结果
     */
    private MessageResp runTurn(RunSession session, String content, List<String> images, TrialProgress progress) {
        Long sessionId = session.getId();
        if (!flight.tryStart(sessionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该会话正在执行");
        }
        // 图片边界校验放在最外层：超量/超大属于请求本身不合法，应在建 trace、写事件之前就拒掉
        List<String> accepted = VisionOcrTexts.normalize(images);
        // 虚拟根没有 Agent 视觉槽：明确拒绝，而不是走一条「模型调用失败」的模糊路径
        if (!accepted.isEmpty() && session.getAgentId() == null) {
            flight.finish(sessionId);
            throw new BizException(ErrorCode.BAD_REQUEST, "虚拟根试跑暂不支持图片输入，请改用文字提问");
        }
        List<RunEvent> written = new ArrayList<>();
        ObsTraceScope trace = null;
        // 正常成功 / 业务失败都算收尾；只有没走到那两支就逃出才把会话收成取消
        boolean settled = false;
        try {
            // 新提问即放弃上一条待批准的计划：旧计划基于旧上下文，继续执行会张冠李戴
            pendingPlans.drop(sessionId);
            // 同理清掉上一轮遗留的子计划待批与批准槽：否则排队中的 P&E 子会被旧槽挡住
            childApprovals.rejectAll(sessionId);
            // 先落 RUNNING，页面在本轮结束前就能看到进行中的调用
            trace = obs.begin(session, content);
            // 虚拟根没有 Agent：agent 可空，但必须有方案（下面 resolve 会校验）
            AgentDef agent = session.getAgentId() == null ? null
                    : agents.findById(session.getAgentId())
                    .orElseThrow(() -> new TrialFailure("Agent 不存在"));
            if (agent != null && (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED)) {
                throw new TrialFailure("Agent 未启用");
            }
            // 执行读源：有 ma_def_id 一律读方案；绑根按「当前启用方案」实时解析；
            // 都没有才回落 Agent 旧配置（在 run() 里写可见提醒）
            MaRunSpec spec = maSpecs.resolve(session, agent);
            // 工作台虚拟根：方案没配判路由模型时回落平台 ROUTER / CHAT 槽；
            // 调试会话（DEBUG）不经过这一步，/api/runtime/trial/** 语义保持原样
            spec = applyWorkbenchJudge(session, spec);
            if (agent == null && spec == null) {
                throw new TrialFailure("该会话既没有 Agent 也没有可用的编排方案，无法执行");
            }
            // 前置拦截：带图却没有 VISION 槽时，必须在写任何事件之前就用明确文案拒绝，
            // 否则用户会看到一条莫名其妙的「模型调用失败」
            String visionNote = null;
            if (!accepted.isEmpty()) {
                if (progress != null) {
                    // OCR 是整轮里最慢的一段（视觉模型首字延迟高）：先推一个阶段，
                    // 否则用户会盯着「任务规划中」不知道后端在做什么
                    progress.phase("vision");
                }
                visionNote = ocrNote(agent, accepted, session);
                if (progress != null) {
                    // 识别结果立刻推给前端：用户能先确认「这张图提对了没有」，再看主模型怎么答
                    progress.vision(visionNote);
                }
            }
            int seq = events.nextSeq(sessionId);
            int userSeq = seq;
            written.add(writer.append(sessionId, seq++, EventType.USER, content, null, null));
            // OCR 作为独立事件下发：时间线上要能看出「这张图提了哪些字」，
            // 否则用户只看到模型答了些什么，无法判断是识别错了还是模型答错了
            if (visionNote != null && !visionNote.isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.VISION, visionNote, null, null));
            }
            // 同一批增量同时镜像到内存快照：刷新页面后前端靠它继续看「边跑边写」，不必等整轮落库。
            // 只在有真实出站时镜像：progress 为 null 是同步 send 路径，collector 会走「结束时一次性补发」
            // 的分支，塞一个非空 progress 进去会改变那条路径的行为，属于不必要的副作用。
            TrialProgress mirrored = progress;
            if (progress != null) {
                // 快照从用户事件那一序号起，并把识别结果一并带上：
                // 刷新后 SSE 已断，快照是唯一能看到「本轮依据的图片文字」的来源
                live.begin(sessionId, userSeq, content, visionNote);
                mirrored = TrialLiveBuffer.tee(progress, live.recorder(sessionId));
            }
            TrialTurn turn = run(agent, spec, content, visionNote, session, written, seq, mirrored);
            obs.finishOk(trace);
            MessageResp done = succeed(session, turn, written, content);
            settled = true;
            return done;
        } catch (TrialFailure ex) {
            // TrialFailure 映射处：文案已是人话，挂了 cause 时补一次全栈，
            // 否则「连通测成功、试跑失败」在控制台仍只剩一句业务短句
            logTrialFailure("试跑失败", sessionId, ex, ex.getMessage());
            obs.finishError(trace, ex.getMessage(), ex.getFinishReason(), ex.getCause());
            MessageResp failed = fail(session, written, ex.getMessage(), content);
            settled = true;
            return failed;
        } catch (RuntimeException ex) {
            // 同步路径兜底 catch：带栈打，保证「再传图失败」时控制台有完整栈
            log.warn("试跑失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            obs.finishError(trace, "试跑失败", null, ex);
            MessageResp failed = fail(session, written, "试跑失败", content);
            settled = true;
            return failed;
        } finally {
            PromptInjectionHolder.clear();
            obs.clear();
            // 整轮结束（无论成败）：内存快照完成使命，权威数据是刚落库的事件
            live.clear(sessionId);
            flight.finish(sessionId);
            // 请求被掐断、fail 写库再抛：错误事件可能没落上，不收口的话刷新后会一直续订
            if (!settled) {
                staleRuns.abandonIfIdle(sessionId);
            }
        }
    }

    /**
     * 多模态预处理：把图片交给 VISION 槽模型提文字，返回可插进用户侧的识别结果。
     *
     * <p>为什么在进入 ReAct 之前做：主链路（ReAct / Router / 计划执行）只认纯文本，
     * OCR 收口在这里，三套编排都不必各自处理图片。
     *
     * <p>失败策略：VISION 槽缺失 → 抛业务异常直接拒绝（前端已置灰上传，这里是最后一道闸）；
     * 识别本身失败 → 抛 {@link TrialFailure}，不带着空图继续让主模型盲答。
     *
     * @param agent   已启用的 Agent 定义
     * @param images  已校验的图片列表
     * @param session 当前会话
     * @return 汇总后的 OCR 文本，永不为 null
     * @throws TrialFailure 未绑定 VISION 槽，或识别失败
     */
    private String ocrNote(AgentDef agent, List<String> images, RunSession session) {
        Long visionInstanceId = loader.requireVisionModel(agent.getId());
        MeterScope scope = MeterScope.console(agent.getId(), visionInstanceId,
                session.getId(), operatorContext.requireUserId());
        // 视觉请求是阻塞的，本方法在请求线程上跑：整个试跑本来就是同步语义，这里不必再切线程。
        // 失败不在这里打日志：VisionOcrExecutor 已带 Throwable 打过一次全栈，
        // 外层 TrialFailure 分支也会再打一次；此处重复打同一份栈只会把控制台刷乱
        String text = vision.extract(images, visionInstanceId, scope);
        if (text == null || text.isBlank()) {
            throw new TrialFailure("未能从图片中提取到文字，请换一张更清晰的图片重试");
        }
        return text;
    }

    private TrialTurn run(AgentDef agent, MaRunSpec spec, String content, String ocrText, RunSession session,
                          List<RunEvent> written, int seq, TrialProgress progress) {
        long userId = session.getUserId();
        // 方案会话（spec 非空）以方案自己为根：没有 Agent 记忆策略，直接用原文，
        // 避免把方案会话写进某个 Agent 的窗口；历史绑根会话即便还带 agentId 也按此口径。
        boolean schemeTurn = spec != null;
        String modelUser = schemeTurn || agent == null ? content : memory.prepareUser(session, content);
        // 多模态：识别结果只拼到「送给模型的那一版」，用户事件与标题仍保持原文
        String query = VisionOcrTexts.composeQuery(ocrText, content);
        modelUser = VisionOcrTexts.applyToModelUser(modelUser, ocrText, content);
        // 运行模式：方案会话固定 ROUTER（方案只有一层编排语义），其余按 Agent / 会话快照
        String runMode = schemeTurn ? AgentRunMode.ROUTER.name()
                : (agent != null ? agent.getRunMode() : session.getRunMode());
        if (AgentRunMode.ROUTER.name().equals(runMode)) {
            if (spec == null) {
                // 绑根但没有启用方案：回落 Agent 旧配置，必须在时间线留痕，避免用户以为读的是方案
                written.add(writer.append(session.getId(), seq++, EventType.SYSTEM,
                        "该编排根未绑定启用中的方案，本轮按 Agent 侧旧配置执行；到「多智能体」为它启用方案后，"
                                + "执行读源才会切到方案（模板 / 候选 / 判路由模型）", null, null));
            }
            // 方案就是根：不再合成 AgentDef 壳，执行器直接以方案（spec）为根跑；
            // 只有旧 Agent 试跑路径（spec 为空）才把真实 Agent 传进去。
            AgentDef root = schemeTurn ? null : agent;
            // 路由判定也要看得到图片里的文字，否则「这张发票什么时候到期」会判不出意图
            RouterTrialExecutor.RoutedTurn routed = router.execute(root, spec, query, modelUser, userId,
                    session.getId(), progress, workbenchHint(session));
            // 刀3：单意图成功轮写回沿用线路，多意图轮清空；失败 / 取消走不到这里，不改旧值
            applyStickyRoute(session, routed);
            // 按子分段落库：决策块 + 每子「路由到」+ 该子 traces，禁止拼成单条时间线
            seq = timeline.appendRouterSegments(session.getId(), seq, routed, written);
            seq = appendNotices(session.getId(), seq, routed.notices(), written);
            written.add(timeline.assistant(session.getId(), seq, routed.turn()));
            if (!schemeTurn && agent != null) {
                memory.afterAssistant(session, routed.turn().getAssistantText());
            }
            return routed.turn();
        }
        if (agent == null) {
            // 方案试跑只支持一层编排；其它模式没有 Agent 时无法装配，直接中文失败
            throw new TrialFailure("方案试跑只支持 ROUTER 编排，请检查方案模板与绑定关系");
        }
        if (AgentRunMode.PLAN_AND_EXECUTE.name().equals(agent.getRunMode())) {
            return runPlan(agent, query, modelUser, session, written, seq, progress);
        }
        // 记忆段在装配时进链（位于工具目录之前），不再往装配结果尾部追加。
        // 传本轮 query（含 OCR 文本）：LONG 走语义召回，取不到就自动回落 MySQL Top N
        TrialMemoryCoordinator.Augmented recalled = memory.recallDurable(session, query);
        TrialPlan plan = loader.loadReact(agent, query, recalled.memoryBlock());
        plan.setToolArgsFacts(recalled.injectedFacts());
        memory.publishInjection(plan);
        seq = appendNotices(session.getId(), seq, plan.getNotices(), written);
        // 纯 ReAct 挂一个 AGENT 根，模型和工具都做它的子步骤
        ObsTraceScope trace = ObsTraceHolder.current();
        Long agentSpan = null;
        if (trace != null) {
            agentSpan = trace.open(SpanType.AGENT, agent.getName(), agent.getId(), null, null);
            trace.bindParent(agentSpan);
        }
        try {
            TrialTurn turn = react.execute(plan, modelUser, userId, session.getId(), progress);
            if (trace != null) {
                // AGENT 只写短摘要，不落长输入输出
                SpanClose agentOk = new SpanClose();
                agentOk.setStatus(ObsStatus.OK);
                agentOk.setOutputText("执行完成");
                agentOk.setAttrsJson(ObsAttrs.withBrief(null, "执行 Agent"));
                trace.close(agentSpan, agentOk);
            }
            seq = timeline.appendTraces(session.getId(), seq, turn, written);
            written.add(timeline.assistant(session.getId(), seq, turn));
            memory.afterAssistant(session, turn.getAssistantText());
            return turn;
        } catch (RuntimeException ex) {
            // AGENT 根步骤失败：先打全栈，再带脱敏 cause 关 span，页面与日志两侧都能定位
            log.warn("Agent 执行失败 agentId={} type={} cause={}",
                    agent.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            if (trace != null) {
                SpanClose agentErr = new SpanClose();
                agentErr.setStatus(ObsStatus.ERROR);
                agentErr.setErrorSummary(ex.getMessage());
                agentErr.setOutputText(ex.getMessage());
                agentErr.setAttrsJson(ObsAttrs.withCause(ObsAttrs.withBrief(null, "Agent 失败"), CauseDigest.of(ex)));
                trace.close(agentSpan, agentErr);
            }
            throw ex;
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
    }

    /**
     * 工作台方案会话的判路由模型回落。
     *
     * <p>方案自己配了判路由模型就尊重方案；没配才用平台 ROUTER → CHAT 槽。
     * 只对 {@code entry_channel=WORKBENCH} 的会话生效，调试入口的
     * 「未配置 → 使用第一条候选」行为完全不变。
     *
     * @param session 当前会话
     * @param spec    方案执行来源，可空
     * @return 可能补了判路由模型的执行来源
     */
    private MaRunSpec applyWorkbenchJudge(RunSession session, MaRunSpec spec) {
        if (spec == null || spec.judgeModelInstanceId() != null) {
            return spec;
        }
        if (!EntryChannel.isWorkbench(session.getEntryChannel())) {
            return spec;
        }
        return spec.withJudgeModel(reception.platformJudgeModel().orElse(null));
    }

    /**
     * 组装本轮的线路提示（刀3）。所属域：runtime。
     *
     * <p>只有工作台正式对话会返回非空：
     * <ul>
     *   <li>调试会话返回 {@code null}，判路由链路与刀3 之前完全一致；</li>
     *   <li>有一次性强制标记时消费掉它（{@link WorkbenchRouteForceRegistry#take(Long)}），
     *       保证「手动换线」只作用一轮；</li>
     *   <li>没有强制标记但有沿用线路时，只把「优先沿用」注入判路由提示词。</li>
     * </ul>
     *
     * @param session 当前会话
     * @return 线路提示；无提示时为空
     */
    private RouteHint workbenchHint(RunSession session) {
        if (!EntryChannel.isWorkbench(session.getEntryChannel())) {
            return null;
        }
        String sticky = session.getStickyRouteKey();
        String forced = routeForce.take(session.getId()).orElse(null);
        if (forced == null) {
            return sticky == null ? null : RouteHint.sticky(sticky);
        }
        return RouteHint.forced(sticky, forced);
    }

    /**
     * 回写线路粘性（刀3）。所属域：runtime。
     *
     * <p>边界：
     * <ul>
     *   <li>只有工作台会话写；调试会话不因试跑而改变线路；</li>
     *   <li>待批准（本轮没有终答）不写：这一轮还没结束，不能把「已定线」提前落库；</li>
     *   <li>{@code sticky == null}（固定模板 / 评审环 / 竞争选优）保持原值不动。</li>
     * </ul>
     *
     * @param session 当前会话
     * @param routed  本轮路由结果
     */
    private void applyStickyRoute(RunSession session, RouterTrialExecutor.RoutedTurn routed) {
        RouteSticky sticky = routed.sticky();
        if (sticky == null || !EntryChannel.isWorkbench(session.getEntryChannel())) {
            return;
        }
        if (routed.turn() != null && routed.turn().isAssistantSuppressed()) {
            return;
        }
        sessions.updateStickyRoute(session.getId(), sticky.routeKey(), sticky.agentId(), session.getUserId());
        // 同步本地对象：同一请求内后续若再读会话，不会拿到旧线路
        session.setStickyRouteKey(sticky.routeKey());
        session.setStickyAgentId(sticky.agentId());
    }

    private int appendNotices(Long sessionId, int seq, List<String> notices, List<RunEvent> written) {
        if (notices == null) {
            return seq;
        }
        for (String notice : notices) {
            written.add(writer.append(sessionId, seq++, EventType.SYSTEM, notice, null, null));
        }
        return seq;
    }

    /**
     * 计划执行模式的一轮：规划 →（可选）停住等批准 → 串行执行 → 汇总终答。
     *
     * <p>「停住等批准」只把计划落库并把上下文留在 {@link PlanPendingRegistry}，本轮以
     * assistantSuppressed 结束：不写空助手事件、不推进记忆窗口。用户点批准后由
     * {@link #approvePlan(Long)} 续跑，点取消则落一条 REJECTED 计划事件。
     *
     * @param agent     已启用的 PLAN_AND_EXECUTE Agent
     * @param content   用户原文
     * @param modelUser 已注入记忆的用户侧文本（规划与执行复用，避免摘要被推进两次）
     * @param session   当前会话
     * @param written   本轮已写事件，计划类事件追加进去
     * @param seq       下一条事件序号
     * @param progress  增量出口，可空
     * @return 终答；待批准时为空正文且 assistantSuppressed=true
     */
    private TrialTurn runPlan(AgentDef agent, String content, String modelUser, RunSession session,
                              List<RunEvent> written, int seq, TrialProgress progress) {
        // 计划类增量既要出站也要落库：用旁路把 plan / step 按顺序收集，整轮结束统一写入
        PlanProgressRecorder recorder = new PlanProgressRecorder(progress);
        PlanTrialExecutor.PlannedTurn planned;
        try {
            planned = planRunner.plan(agent, content, modelUser, session.getUserId(), session.getId(), recorder);
        } catch (RuntimeException ex) {
            // 规划失败也要把已产生的帧写掉，否则时间线上看不到「为什么没有计划」
            log.warn("计划阶段失败 sessionId={} type={} cause={}",
                    session.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            timeline.appendPlanFrames(session.getId(), seq, recorder.drain(), written);
            throw ex;
        }
        seq = timeline.appendPlanFrames(session.getId(), seq, recorder.drain(), written);
        seq = appendNotices(session.getId(), seq, planned.plan().getNotices(), written);

        PendingPlan pending = new PendingPlan(planned.planId(), session.getId(), agent.getId(), session.getUserId(),
                content, modelUser, planned.steps(), LocalDateTime.now());
        if (planned.state() == PlanState.PENDING) {
            // 只登记不执行：把复用所需的上下文留给批准接口，本轮到此为止
            pendingPlans.put(pending);
            TrialTurn waiting = new TrialTurn();
            waiting.setAssistantSuppressed(true);
            return waiting;
        }
        return executePendingPlan(agent, pending, session, written, seq, recorder);
    }

    /**
     * 按已确定的计划串行执行并汇总。自动执行与「用户批准」两条路径共用这里。
     *
     * <p>失败时也要把已产生的计划/步骤帧写掉：时间线必须能看出「计划执行到第几步失败」，
     * 只留一条 error 会让用户无从判断进度。
     *
     * @param agent      Agent 定义
     * @param pending    待执行计划（含复用所需上下文）
     * @param session    当前会话
     * @param written    本轮已写事件
     * @param seq        下一条事件序号
     * @param recorder   计划类增量旁路；批准路径传入新实例
     * @return 终答
     */
    private TrialTurn executePendingPlan(AgentDef agent, PendingPlan pending, RunSession session,
                                          List<RunEvent> written, int seq, PlanProgressRecorder recorder) {
        PlanTrialExecutor.ExecutedTurn executed;
        try {
            executed = planRunner.execute(agent, pending, recorder);
        } catch (RuntimeException ex) {
            // 失败也要落计划/步骤帧：时间线要能看出「执行到第几步、失败在哪」
            log.warn("计划执行阶段失败 sessionId={} type={} cause={}",
                    session.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            timeline.appendPlanFrames(session.getId(), seq, recorder.drain(), written);
            throw ex;
        }
        seq = timeline.appendPlanFrames(session.getId(), seq, recorder.drain(), written);
        // 计划模式的思考/工具已由 recorder 按发生顺序逐条登记并随帧落库，
        // 这里不能再走 appendTraces：那是整轮合并后的快照，再写一遍会重复，
        // 且会把所有步骤的推理堆到全部步骤之后，破坏「第 N 步 → 该步思考」的顺序。
        written.add(timeline.assistant(session.getId(), seq, executed.turn()));
        memory.afterAssistant(session, executed.turn().getAssistantText());
        return executed.turn();
    }


    private MessageResp succeed(RunSession session, TrialTurn turn, List<RunEvent> written, String userText) {
        return succeed(session, turn, written, userText, true);
    }

    /**
     * @param advance false 表示同一轮的续跑（批准计划），不重复累加轮次
     */
    private MessageResp succeed(RunSession session, TrialTurn turn, List<RunEvent> written, String userText,
                                boolean advance) {
        // 库里的会话继续 RUNNING。响应 status 只表示这一轮成功，供气泡使用
        recordTurn(session, userText, "", advance);
        operLogWriter.write("runtime", "run", "agent_run_session", String.valueOf(session.getId()),
                "试跑会话 " + session.getId() + " 本轮完成");
        // MA 自动标题（刀：自动会话标题）：首轮成功后立刻用首句原文截出标题并写库，
        // 左栏随下一次列表请求即可见；只有截不出标题（原文空白）时才回落既有 LLM 兜底。
        boolean titled = applyFirstMessageTitle(session, userText);
        if (!titled) {
            maybeGenerateTitle(session, userText, turn);
        }
        // 待批准：本轮没有助手正文，预览给一句可读说明，不把空串写进左栏
        String preview = turn.isAssistantSuppressed()
                ? TrialTexts.preview("已生成计划，等待批准后执行")
                : TrialTexts.preview(turn.getAssistantText());
        MessageResp resp = response(session, preview, written);
        resp.setStatus(RunStatus.SUCCESS.name());
        resp.setPlanPending(turn.isAssistantSuppressed());
        return resp;
    }

    /**
     * 首轮成功后写入「首句截断」标题（MA 自动会话标题的唯一规则）。
     *
     * <p>为什么放在请求线程同步写：产品要求左栏在首轮结束后立刻有标题，
     * 交给异步任务会晚 3～8 秒才出现；这里一条带条件的 UPDATE，开销可忽略。
     *
     * <p>边界：
     * <ul>
     *   <li>只在 {@code message_count == 1}（首轮）写，后续轮次不再动标题；</li>
     *   <li>用户已手改（{@code title_source=MANUAL}）或已有标题时，
     *       {@link RunSessionRepository#updateAutoTitleIfAbsent} 的条件更新会命中 0 行，
     *       标题保持不变——这就是「手改后不再覆盖」的实现；</li>
     *   <li>虚拟根会话同样生效：只依赖会话行，不需要 Agent。</li>
     * </ul>
     *
     * @param session  本轮会话（轮次已累加）
     * @param userText 用户这一轮原文
     * @return true 表示已经存在确定性标题（无论本次是否真的写入），调用方不必再走 LLM 兜底
     */
    private boolean applyFirstMessageTitle(RunSession session, String userText) {
        if (session == null || session.getId() == null || safeCount(session) != 1) {
            return false;
        }
        String title = SessionTitleTexts.fromFirstMessage(userText);
        if (title.isEmpty()) {
            return false;
        }
        boolean written = sessions.updateAutoTitleIfAbsent(session.getId(), title, operatorContext.requireUserId());
        if (written) {
            // 本地对象同步成已写状态：后续同一轮的续跑不会重复尝试（条件更新本就只会命中一次）
            session.setSessionTitle(title);
            session.setTitleSource(TitleSource.AUTO.name());
        }
        return true;
    }

    /**
     * 首轮结束后触发异步标题生成（LLM 兜底，仅当首句截断写不出标题时才走到这里）。
     *
     * <p>本刀（自动会话标题）的正常路径是 {@link #applyFirstMessageTitle}：首句原文直接截断入库、
     * 不产生任何出站；本方法保留为既有能力复用，处理「用户原文全是空白」这类极端输入。
     *
     * <p>为什么只在 message_count == 1 时触发：标题概括的是会话主题，首轮就定了；
     * 后续轮次再调模型纯属浪费 token。批准计划那条路径的 advance=false、轮次仍是 1，
     * 因此首轮「计划待批准 → 批准执行完」也能补上标题。
     *
     * <p>为什么这里不判标题是否已存在：判定要在异步线程读最新库值才准，
     * 由 {@link SessionTitleGeneratorAppService} 自己做；这里只做「是否首轮」这一层最省事的过滤。
     *
     * @param session  本轮会话（轮次已累加）
     * @param userText 用户这一轮原文
     * @param turn     本轮结果；助手正文为空（待批准）时不触发
     */
    private void maybeGenerateTitle(RunSession session, String userText, TrialTurn turn) {
        if (turn == null || turn.isAssistantSuppressed()) {
            return;
        }
        String answer = turn.getAssistantText();
        if (answer == null || answer.isBlank()) {
            return;
        }
        if (safeCount(session) != 1) {
            return;
        }
        if (session.getAgentId() == null) {
            return;
        }
        try {
            titleGenerator.asyncGenerateTitle(session.getId(), session.getAgentId(), userText, answer);
        } catch (RuntimeException ex) {
            // 线程池拒绝等提交期异常：标题可以没有，本轮响应必须正常返回
            log.warn("提交会话标题生成任务失败 sessionId={} type={} cause={}",
                    session.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
        }
    }

    private MessageResp fail(RunSession session, List<RunEvent> written, String reason, String userText) {
        return fail(session, written, reason, userText, true);
    }

    /**
     * @param advance false 表示同一轮的续跑（批准计划），不重复累加轮次
     */
    private MessageResp fail(RunSession session, List<RunEvent> written, String reason, String userText,
                             boolean advance) {
        int seq = events.nextSeq(session.getId());
        written.add(writer.append(session.getId(), seq, EventType.ERROR, reason, null, null));
        String summary = TrialTexts.summary(reason);
        recordTurn(session, userText, summary, advance);
        operLogWriter.write("runtime", "run", "agent_run_session", String.valueOf(session.getId()),
                "试跑会话 " + session.getId() + " 本轮失败");
        MessageResp resp = response(session, "", written);
        resp.setStatus(RunStatus.FAILED.name());
        resp.setErrorSummary(summary);
        return resp;
    }

    /**
     * 推进轮次和预览，清掉或写上本轮错误。不把会话收成终态。
     *
     * @param advance true 表示这是新的一轮（用户刚发问）；批准/取消同一轮时传 false，
     *                否则 user 事件那一轮已被计过一次，这里会把轮次算两遍
     */
    private void recordTurn(RunSession session, String userText, String errorSummary, boolean advance) {
        TrialTurnOutcome.keepOpen(session);
        if (advance) {
            session.setMessageCount(safeCount(session) + 1);
        }
        ensureTitle(session, userText);
        session.setErrorSummary(errorSummary);
        sessions.updateOutcome(session, operatorContext.requireUserId());
    }

    private MessageResp response(RunSession session, String preview, List<RunEvent> written) {
        MessageResp resp = new MessageResp();
        resp.setSessionId(session.getId());
        resp.setStatus(session.getStatus());
        resp.setAssistantPreview(preview);
        resp.setErrorSummary(session.getErrorSummary());
        resp.setEvents(written.stream().map(TrialSessionAppService::toEvent).toList());
        return resp;
    }

    /**
     * 只在还没有预览时用用户原文生成 last_msg_preview。
     *
     * <p>严禁在这里写 session_title：首轮开始时系统只落一条副标题预览；
     * 主标题由 {@link #applyFirstMessageTitle} 在本轮成功后用首句原文截断写入
     * （写不出时才由 {@link SessionTitleGeneratorAppService} 兜底），或由用户手动改名产生。
     * 若这里顺手写了标题，条件更新会因为「已有值」而跳过，标题就等于绕开了统一规则。
     *
     * @param session  会话
     * @param userText 用户原文
     */
    private void ensureTitle(RunSession session, String userText) {
        if (session.getLastMsgPreview() == null || session.getLastMsgPreview().isBlank()) {
            session.setLastMsgPreview(TrialTexts.preview(userText));
        }
    }

    private int safeCount(RunSession session) {
        return session.getMessageCount() == null ? 0 : session.getMessageCount();
    }
}

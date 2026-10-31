package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
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
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.PlanJson;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialSendGate;
import com.agenthub.runtime.domain.service.TrialTurnOutcome;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.ToolResultClipper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 发送一轮试跑。所属域：runtime。模型调用在事务外，成功或失败后再落事件。
 * 会话在多轮期间保持 RUNNING，不写结束时间。操作日志只记会话，不记用户原文、助手正文和工具实参。
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
    private final TrialEventWriter writer;
    private final TrialFlight flight;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final TrialObsHooks obs;
    private final TrialMemoryCoordinator memory;
    private final TrialLiveBuffer live;

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
     * @param writer          摘要入库
     * @param flight          单飞
     * @param operatorContext 当前用户
     * @param operLogWriter   动作日志
     * @param obs             调用追踪。失败不影响本轮试跑
     * @param memory          窗口、记忆注入和显式记住
     * @param live            进行中一轮的内存快照，供刷新后的只读轮询
     */
    public TrialRunAppService(RunSessionRepository sessions, RunEventRepository events, AgentDefRepository agents,
                              TrialAccess access, TrialBindingLoader loader, ReActTrialExecutor react,
                              RouterTrialExecutor router, PlanTrialExecutor planRunner,
                              PlanPendingRegistry pendingPlans, TrialEventWriter writer, TrialFlight flight,
                              OperatorContext operatorContext, OperLogWriter operLogWriter, TrialObsHooks obs,
                              TrialMemoryCoordinator memory, TrialLiveBuffer live) {
        this.sessions = sessions;
        this.events = events;
        this.agents = agents;
        this.access = access;
        this.loader = loader;
        this.react = react;
        this.router = router;
        this.planRunner = planRunner;
        this.pendingPlans = pendingPlans;
        this.writer = writer;
        this.flight = flight;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.obs = obs;
        this.memory = memory;
        this.live = live;
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
        return executeTurn(sessionId, content, null);
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
        try {
            progress.phase("planning");
            end.done(executeTurn(sessionId, content, progress));
        } catch (BizException ex) {
            end.fail(ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("试跑流失败 sessionId={} type={}", sessionId, ex.getClass().getSimpleName());
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
        try {
            trace = obs.begin(session, pending.userText());
            AgentDef agent = agents.findById(session.getAgentId())
                    .orElseThrow(() -> new TrialFailure("Agent 不存在"));
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
            return succeed(session, turn, written, pending.userText(), false);
        } catch (TrialFailure ex) {
            obs.finishError(trace, ex.getMessage(), ex.getFinishReason());
            return fail(session, written, ex.getMessage(), pending.userText(), false);
        } catch (RuntimeException ex) {
            log.warn("批准计划后执行失败 sessionId={} type={}", sessionId, ex.getClass().getSimpleName());
            obs.finishError(trace, "试跑失败", null);
            return fail(session, written, "试跑失败", pending.userText(), false);
        } finally {
            PromptInjectionHolder.clear();
            obs.clear();
            // 本轮结束：内存快照完成使命，权威数据是刚落库的事件
            live.clear(sessionId);
            flight.finish(sessionId);
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

    private MessageResp executeTurn(Long sessionId, String content, TrialProgress progress) {
        RunSession session = access.require(sessionId);
        // 仅取消（及已删除由 access 挡）不可再发。历史 SUCCESS 不挡；空闲不再写成 SUCCESS。
        TrialSendGate.assertSendable(session);
        if (!flight.tryStart(sessionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该会话正在执行");
        }
        List<RunEvent> written = new ArrayList<>();
        ObsTraceScope trace = null;
        try {
            // 新提问即放弃上一条待批准的计划：旧计划基于旧上下文，继续执行会张冠李戴
            pendingPlans.drop(sessionId);
            // 先落 RUNNING，页面在本轮结束前就能看到进行中的调用
            trace = obs.begin(session, content);
            AgentDef agent = agents.findById(session.getAgentId())
                    .orElseThrow(() -> new TrialFailure("Agent 不存在"));
            if (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED) {
                throw new TrialFailure("Agent 未启用");
            }
            int seq = events.nextSeq(sessionId);
            written.add(writer.append(sessionId, seq++, EventType.USER, content, null, null));
            // 同一批增量同时镜像到内存快照：刷新页面后前端靠它继续看「边跑边写」，不必等整轮落库。
            // 只在有真实出站时镜像：progress 为 null 是同步 send 路径，collector 会走「结束时一次性补发」
            // 的分支，塞一个非空 progress 进去会改变那条路径的行为，属于不必要的副作用。
            TrialProgress mirrored = progress;
            if (progress != null) {
                live.begin(sessionId, seq - 1, content);
                mirrored = TrialLiveBuffer.tee(progress, live.recorder(sessionId));
            }
            TrialTurn turn = run(agent, content, session, written, seq, mirrored);
            obs.finishOk(trace);
            return succeed(session, turn, written, content);
        } catch (TrialFailure ex) {
            obs.finishError(trace, ex.getMessage(), ex.getFinishReason());
            return fail(session, written, ex.getMessage(), content);
        } catch (RuntimeException ex) {
            log.warn("试跑失败 sessionId={} type={}", sessionId, ex.getClass().getSimpleName());
            obs.finishError(trace, "试跑失败", null);
            return fail(session, written, "试跑失败", content);
        } finally {
            PromptInjectionHolder.clear();
            obs.clear();
            // 整轮结束（无论成败）：内存快照完成使命，权威数据是刚落库的事件
            live.clear(sessionId);
            flight.finish(sessionId);
        }
    }

    private TrialTurn run(AgentDef agent, String content, RunSession session, List<RunEvent> written, int seq,
                           TrialProgress progress) {
        long userId = session.getUserId();
        // 用户事件已经入库。这里追加窗口并在触顶时滚动摘要，再把摘要和短期记忆拼进本轮
        String modelUser = memory.prepareUser(session, content);
        if (AgentRunMode.ROUTER.name().equals(agent.getRunMode())) {
            RouterTrialExecutor.RoutedTurn routed = router.execute(agent, content, modelUser, userId, session.getId(), progress);
            // 按子分段落库：决策块 + 每子「路由到」+ 该子 traces，禁止拼成单条时间线
            seq = appendRouterSegments(session.getId(), seq, routed, written);
            seq = appendNotices(session.getId(), seq, routed.notices(), written);
            written.add(assistant(session.getId(), seq, routed.turn()));
            memory.afterAssistant(session, routed.turn().getAssistantText());
            return routed.turn();
        }
        if (AgentRunMode.PLAN_AND_EXECUTE.name().equals(agent.getRunMode())) {
            return runPlan(agent, content, modelUser, session, written, seq, progress);
        }
        TrialPlan plan = loader.loadReact(agent, content);
        // 记忆段与「本轮已注入的长期记忆」一次取回，工具补参与提示词用同一份依据
        TrialMemoryCoordinator.Augmented augmented = memory.augmentSystemWithFacts(session, plan.getSystemPrompt());
        plan.setSystemPrompt(augmented.systemPrompt());
        plan.setToolArgsFacts(augmented.injectedFacts());
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
            seq = appendTraces(session.getId(), seq, turn, written);
            written.add(assistant(session.getId(), seq, turn));
            memory.afterAssistant(session, turn.getAssistantText());
            return turn;
        } catch (RuntimeException ex) {
            if (trace != null) {
                SpanClose agentErr = new SpanClose();
                agentErr.setStatus(ObsStatus.ERROR);
                agentErr.setErrorSummary(ex.getMessage());
                agentErr.setOutputText(ex.getMessage());
                agentErr.setAttrsJson(ObsAttrs.withBrief(null, "Agent 失败"));
                trace.close(agentSpan, agentErr);
            }
            throw ex;
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
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
            appendPlanFrames(session.getId(), seq, recorder.drain(), written);
            throw ex;
        }
        seq = appendPlanFrames(session.getId(), seq, recorder.drain(), written);
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
            appendPlanFrames(session.getId(), seq, recorder.drain(), written);
            throw ex;
        }
        seq = appendPlanFrames(session.getId(), seq, recorder.drain(), written);
        // 计划模式的思考/工具已由 recorder 按发生顺序逐条登记并随帧落库，
        // 这里不能再走 appendTraces：那是整轮合并后的快照，再写一遍会重复，
        // 且会把所有步骤的推理堆到全部步骤之后，破坏「第 N 步 → 该步思考」的顺序。
        written.add(assistant(session.getId(), seq, executed.turn()));
        memory.afterAssistant(session, executed.turn().getAssistantText());
        return executed.turn();
    }

    /**
     * 把收集到的时间线帧写成事件。PLAN / STEP 带结构化 JSON 正文，前端按 planId / seq 还原结构；
     * REASONING / TOOL 是计划阶段与各步内部的思考与工具，按发生顺序落在对应位置。
     *
     * @param frames 按发生顺序的帧
     * @return 推进后的下一条序号
     */
    private int appendPlanFrames(Long sessionId, int seq, List<PlanProgressRecorder.Frame> frames,
                                 List<RunEvent> written) {
        if (frames == null) {
            return seq;
        }
        for (PlanProgressRecorder.Frame frame : frames) {
            written.add(writer.append(sessionId, seq++, frame.type(), frame.summary(), frame.content(), null, null));
        }
        return seq;
    }

    /**
     * Router 思考按子分段落库：可选决策块 → 每子一条 ROUTE → 该子 reasoning/tool。
     * 无 segments 时退回旧的单条 summary + 合并 traces（兼容）。
     */
    private int appendRouterSegments(Long sessionId, int seq, RouterTrialExecutor.RoutedTurn routed,
                                     List<RunEvent> written) {
        if (routed.decisionBlock() != null && !routed.decisionBlock().isBlank()) {
            written.add(writer.append(sessionId, seq++, EventType.ROUTE, routed.decisionBlock(), null, null));
        }
        List<RouterTrialExecutor.RouteSegment> segments = routed.segments();
        if (segments == null || segments.isEmpty()) {
            if (routed.summary() != null && !routed.summary().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.ROUTE, routed.summary(), null, null));
            }
            return appendTraces(sessionId, seq, routed.turn(), written);
        }
        for (RouterTrialExecutor.RouteSegment segment : segments) {
            if (segment.routeLine() != null && !segment.routeLine().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.ROUTE, segment.routeLine(), null, null));
            }
            if (segment.error() != null && !segment.error().isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.SYSTEM,
                        "子 Agent 失败：" + segment.error(), null, null));
            }
            if (segment.turn() != null) {
                seq = appendTraces(sessionId, seq, segment.turn(), written);
            }
        }
        return seq;
    }

    /**
     * 按引擎顺序写思考和工具。空白思考跳过，避免没有中间步时造出假 reasoning。
     * 工具行带脱敏参数。结果另起一段放正文。没有参数时不补空 JSON。
     */
    private int appendTraces(Long sessionId, int seq, TrialTurn turn, List<RunEvent> written) {
        if (turn.getTraces() == null) {
            return seq;
        }
        for (TrialTrace trace : turn.getTraces()) {
            if (trace == null || trace.getType() == null) {
                continue;
            }
            if (trace.getType() == EventType.REASONING) {
                if (trace.getContent() == null || trace.getContent().isBlank()) {
                    continue;
                }
                written.add(writer.append(sessionId, seq++, EventType.REASONING, trace.getContent(), null, null));
                continue;
            }
            if (trace.getType() == EventType.TOOL) {
                String line = TrialTexts.toolDisplay(trace.getName(), trace.getArguments());
                if (!trace.isSuccess()) {
                    line = line + " · 失败";
                }
                // 时间线正文与回灌同截并可见截断标记；obs TOOL span 仍用 traces 脱敏全文
                String result = ToolResultClipper.clipText(trace.getContent(), turn.getToolResultMaxChars());
                String body = result == null || result.isBlank() ? line : line + "\n\n" + result;
                written.add(writer.append(sessionId, seq++, EventType.TOOL, line, body, null, null));
            }
        }
        return seq;
    }

    private RunEvent assistant(Long sessionId, int seq, TrialTurn turn) {
        // 摘要给列表；正文走 content_text，保留 Markdown 换行，不在这里折行
        return writer.append(sessionId, seq, EventType.ASSISTANT, turn.getAssistantText(),
                turn.getPromptTokens(), turn.getCompletionTokens());
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
        // 待批准：本轮没有助手正文，预览给一句可读说明，不把空串写进左栏
        String preview = turn.isAssistantSuppressed()
                ? TrialTexts.preview("已生成计划，等待批准后执行")
                : TrialTexts.preview(turn.getAssistantText());
        MessageResp resp = response(session, preview, written);
        resp.setStatus(RunStatus.SUCCESS.name());
        resp.setPlanPending(turn.isAssistantSuppressed());
        return resp;
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
     * 标题只在还没有预览时用用户原文生成。之后发送不再覆盖，方便左栏改名。
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

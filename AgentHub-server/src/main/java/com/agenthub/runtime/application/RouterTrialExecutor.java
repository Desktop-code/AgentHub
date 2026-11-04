package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.RouterAnswerMode;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.agent.domain.service.AgentCriticPolicy;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.ScratchBoard;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.ChildSubQueryInput;
import com.agenthub.runtime.domain.service.CriticPrompt;
import com.agenthub.runtime.domain.service.CriticVerdictParser;
import com.agenthub.runtime.domain.service.FixedSequenceContext;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import com.agenthub.runtime.domain.service.RouteDecisionParser;
import com.agenthub.runtime.domain.service.RouteKeywordPicker;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.ScratchContext;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一层 Router 试跑执行器。所属域：runtime。
 *
 * <p>按 {@code runtime_config_json.maTemplate} 早分支五套拓扑：
 * <ul>
 *   <li>{@code ROUTER_PARALLEL}：判路由输出有序 [{route_key, sub_query}] → ≥2 子并行 ReAct → 终答 CONCAT/SUMMARIZE；</li>
 *   <li>{@code FIXED_PARALLEL}：跳过判路由，全部启用候选吃同一份用户原问并行执行（超上限按序跳过）；</li>
 *   <li>{@code FIXED_SEQUENCE}：跳过判路由，按 sort_order 串行 S1→S2→…，后步可见前置终答摘要；</li>
 *   <li>{@code CRITIC_LOOP}：跳过判路由，恰好 1 个执行者出稿 → 评审专家判定 → 有限回修，
 *       终答＝最后一版执行者输出（不拼接、不汇总）。</li>
 *   <li>{@code ROUTER_THEN_SEQUENCE}：判路由契约与 ROUTER_PARALLEL 相同，但命中子按<b>判定数组序</b>
 *       串行 S1→S2→…（复用固定串行的前置摘要纪律），最后 CONCAT/SUMMARIZE。</li>
 * </ul>
 * 多意图子执行强制隔离上下文（仅 sub_query，见 {@link ChildSubQueryInput}）；单意图可继承父历史。
 * 思考过程按子分段推送/落库，禁止收束拍扁成单条路由摘要。一子失败其它继续。
 * 观测挂点：根 ROUTE → （判路由模板：判路由 LLM）→（子 AGENT∥…/→…）→ 子内；子 AGENT 禁止挂 ROUTE/根。
 */
@Component
public class RouterTrialExecutor {

    private static final Logger log = LoggerFactory.getLogger(RouterTrialExecutor.class);

    private final AgentRouteRepository routes;
    private final AgentDefRepository agents;
    private final TrialBindingLoader loader;
    private final ReActTrialExecutor react;
    private final SystemPromptAssembler prompts;
    private final RuntimeLimitResolver limitResolver;
    private final TrialMemoryCoordinator memory;
    /** P&E 子派发：规划 / 逐步执行 / 汇总全复用单 Agent 那份实现，不另造执行器 */
    private final PlanTrialExecutor planRunner;
    /** 子计划待批登记与「同时仅 1 个」批准槽 */
    private final ChildPlanApprovalRegistry childApprovals;
    /** 并行子 Agent 的全局执行池；由 AsyncConfig 统一装配，禁止在方法内临时新建 */
    private final ThreadPoolTaskExecutor routerTaskExecutor;

    /**
     * @param routes             子路由
     * @param agents             子 Agent 定义
     * @param loader             ReAct 装配
     * @param react              官方 ReAct 执行
     * @param prompts            父 Agent 判定/汇总也要带平台前缀
     * @param limitResolver      父墙钟与子轮次
     * @param memory             子 Agent 补记忆段并公布注入统计
     * @param planRunner         PLAN_AND_EXECUTE 子执行（规划/逐步/汇总）
     * @param childApprovals     子计划批准槽与待批登记（进程内）
     * @param routerTaskExecutor 并行子执行池（有界队列 + CallerRuns），见 AsyncConfig
     */
    public RouterTrialExecutor(AgentRouteRepository routes, AgentDefRepository agents,
                               TrialBindingLoader loader, ReActTrialExecutor react, SystemPromptAssembler prompts,
                               RuntimeLimitResolver limitResolver, TrialMemoryCoordinator memory,
                               PlanTrialExecutor planRunner, ChildPlanApprovalRegistry childApprovals,
                               @Qualifier("routerTaskExecutor") ThreadPoolTaskExecutor routerTaskExecutor) {
        this.routes = routes;
        this.agents = agents;
        this.loader = loader;
        this.react = react;
        this.prompts = prompts;
        this.limitResolver = limitResolver;
        this.memory = memory;
        this.planRunner = planRunner;
        this.childApprovals = childApprovals;
        this.routerTaskExecutor = routerTaskExecutor;
    }

    /**
     * @param parent    已启用的 ROUTER
     * @param userText  用户原文
     * @param userId    平台用户
     * @param sessionId 试跑会话
     * @return 路由说明、子 Agent 的装配提示和执行结果
     */
    public RoutedTurn execute(AgentDef parent, String userText, long userId, long sessionId) {
        return execute(parent, userText, userText, userId, sessionId, null);
    }

    /**
     * 同 {@link #execute(AgentDef, String, long, long)}。子 Agent 的增量交给 progress；
     * 路由判定结果经 {@link TrialProgress#route} 推给前端（可多条）。CONCAT/SUMMARIZE 等齐后再出终答。
     *
     * @param modelUser 已经拼好摘要、近轮和短期记忆的用户侧文本。路由判定仍用原文；子执行用 sub_query 改写
     * @param progress  可空
     * @return 路由说明、子 Agent 的装配提示和执行结果
     */
    public RoutedTurn execute(AgentDef parent, String userText, String modelUser, long userId, long sessionId,
                              TrialProgress progress) {
        EffectiveLimits parentLimits = limitResolver.resolve(parent);
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse(parent.getRuntimeConfigJson(), parent.getRunMode());
        // 共享草稿区（MA-P1 刀7）：只有选中 SHARED_SCRATCH 才开板；板是「本轮 run 级」状态，
        // 挂在线程上下文里随根执行传下去，根结束（成功 / 失败 / 取消）后 close 丢弃，不落库、不跨轮复用
        ScratchBoard scratch = runtime.scratchOn() ? new ScratchBoard() : null;
        if (scratch == null) {
            return executeRun(parent, userText, modelUser, userId, sessionId, progress, runtime, parentLimits);
        }
        try {
            return ScratchContext.callAs(scratch, "", () ->
                    executeRun(parent, userText, modelUser, userId, sessionId, progress, runtime, parentLimits));
        } finally {
            // 取消 / 异常同样走到这里：停止接受新写，已写入内容本轮内仍可读，下一轮直接消失
            scratch.close();
        }
    }

    /**
     * 模板早分支与三条执行链。所属域：runtime。
     *
     * <p>从 {@link #execute(AgentDef, String, String, long, long, TrialProgress)} 拆出来只为让
     * 「建板 / 绑板 / 收板」集中在一个地方，执行语义与拆前完全一致。
     */
    private RoutedTurn executeRun(AgentDef parent, String userText, String modelUser, long userId, long sessionId,
                                  TrialProgress progress, AgentRuntimeConfig runtime, EffectiveLimits parentLimits) {
        // 模板早分支：评审环与固定模板都不判路由，整条执行链与判路由路径完全分开，避免语义互相污染
        if (runtime.isCriticLoop()) {
            return executeCriticLoop(parent, userText, userId, sessionId, progress, runtime, parentLimits);
        }
        if (runtime.isFixedTemplate()) {
            return executeFixed(parent, userText, modelUser, userId, sessionId, progress, runtime, parentLimits);
        }
        // 路由后串行：判路由契约与 ROUTER_PARALLEL 完全相同，只是把扇出换成「按判定序串行」
        if (runtime.isRouterThenSequence()) {
            return executeRouterThenSequence(parent, userText, modelUser, userId, sessionId, progress,
                    runtime, parentLimits);
        }
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        Long routeSpan = trace == null ? null : trace.open(SpanType.ROUTE, "judge", parent.getId(), null, null);
        if (trace != null) {
            trace.bindParent(routeSpan);
        }
        AtomicBoolean routeClosed = new AtomicBoolean(false);
        try {
            assertParentDeadline(deadlineNanos);
            Candidates loaded = loadCandidates(parent.getId(), runtime);
            List<Candidate> candidates = loaded.usable();
            List<BlockedCandidate> blockedCandidates = loaded.blocked();
            if (candidates.isEmpty()) {
                throw new TrialFailure(blockedCandidates.isEmpty()
                        ? "Router 没有可用的子 Agent"
                        : "没有可用的子 Agent：" + blockedCandidates.size()
                                + " 位专家未获本编排允许（" + blockedReasons(blockedCandidates)
                                + "），请检查编排策略与对应 Agent 配置");
            }
            List<RoutedIntent> intents = choose(parent, parentLimits, runtime, userText, candidates,
                    userId, sessionId, deadlineNanos);
            // 关多意图：只保留第一项
            if (!runtime.multiIntentOn() && intents.size() > 1) {
                intents = List.of(intents.get(0));
            }
            List<String> routeLines = new ArrayList<>();
            for (RoutedIntent intent : intents) {
                routeLines.add(routeSummary(intent.candidate(), intent.reason()));
            }
            String decisionJson = RouteDecisionParser.toDecisionJson(intents.stream()
                    .map(item -> new RouteDecisionParser.Item(
                            item.candidate().route().getRouteKey() == null ? "" : item.candidate().route().getRouteKey(),
                            item.subQuery()))
                    .toList());
            // 多意图：先推完整决策块；各子「路由到」改在子开跑时推，避免收束后只剩一条糊在一起的摘要
            if (progress != null) {
                if (intents.size() >= 2) {
                    progress.route("路由决策\n" + decisionJson);
                } else if (!routeLines.isEmpty()) {
                    progress.route(routeLines.get(0));
                }
            }
            if (trace != null) {
                SpanClose routeClose = new SpanClose();
                routeClose.setStatus(ObsStatus.OK);
                routeClose.setOutputText(String.join("；", routeLines));
                routeClose.setRouteDecision(decisionJson);
                if (intents.size() == 1) {
                    routeClose.setChildAgentId(intents.get(0).candidate().agent().getId());
                }
                // attrs 只加编排模板/汇总策略等扁平键：父链结构不动（父链另刀）
                Map<String, Object> routeFields = new LinkedHashMap<>();
                routeFields.put("ma.template", runtime.maTemplateOrDefault());
                routeFields.put("ma.aggregate", runtime.answerModeEnum().name());
                // 上下文策略只落扁平 attrs，不动父链
                routeFields.put("ma.context", runtime.maContextPolicyOrDefault());
                // 共享草稿只写键数 / 冲突次数（有则显示），不写任何 key / value 正文
                routeFields.putAll(scratchAttrs());
                routeClose.setAttrsJson(ObsAttrs.withFields(
                        ObsAttrs.withBrief(null,
                                intents.size() <= 1 ? "路由到 " + childLabel(intents.get(0).candidate().agent())
                                        : "多意图路由×" + intents.size()),
                        routeFields));
                trace.close(routeSpan, routeClose);
                routeClosed.set(true);
            }

            // 多子或 SUMMARIZE：子侧仍推 tool/reasoning；屏蔽 assistant，等齐后一次出终答
            boolean muteChildAssistant = intents.size() > 1
                    || runtime.answerModeEnum() == RouterAnswerMode.SUMMARIZE;
            // 路由列表≥2 强制隔离上下文；单意图继承父历史（兼容追问）
            boolean isolateChild = intents.size() >= 2;
            TrialProgress childProgress = muteChildAssistant ? muteAssistant(progress) : progress;
            // 先链后扇：子 AGENT 挂判路由 LLM，禁止挂 ROUTE/根；无 LLM 时退 ROUTE
            Long childParent = childAgentParent(trace, routeSpan);
            List<ChildOutcome> outcomes = runChildren(parent, intents, modelUser, userText, userId, sessionId,
                    deadlineNanos, childProgress, trace, childParent, isolateChild);
            List<String> notices = new ArrayList<>();
            List<RouteSegment> segments = new ArrayList<>();
            for (int i = 0; i < outcomes.size(); i++) {
                ChildOutcome outcome = outcomes.get(i);
                notices.addAll(outcome.notices());
                String line = i < routeLines.size() ? routeLines.get(i)
                        : routeSummary(outcome.intent().candidate(), outcome.intent().reason());
                segments.add(new RouteSegment(line, outcome.turn(), outcome.error()));
            }
            /*
             * 未允许被编排调用的子不参与判路由，但必须在时间线留「已跳过」：
             * 只写 routeLine + notice，不塞进 outcomes，避免改变终答拼接与流式推送口径。
             */
            for (ChildOutcome blocked : blockedOutcomes(blockedCandidates, userText)) {
                notices.addAll(blocked.notices());
                segments.add(new RouteSegment(blocked.intent().routeLine(), null, null));
            }
            TrialTurn turn = finishAnswer(parent, parentLimits, runtime, userText, outcomes, intents,
                    userId, sessionId, deadlineNanos, progress, trace, routeSpan);
            String decisionBlock = intents.size() >= 2 ? "路由决策\n" + decisionJson : null;
            return new RoutedTurn(String.join("；", routeLines), decisionBlock, segments, notices, turn);
        } catch (RuntimeException ex) {
            // 路由失败同样是关键 catch：带 Throwable 打全栈，并在 ROUTE 步骤写脱敏 cause
            log.warn("Router 执行失败 parentId={} type={} cause={}",
                    parent.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            if (trace != null && !routeClosed.get()) {
                SpanClose routeErr = new SpanClose();
                routeErr.setStatus(ObsStatus.ERROR);
                routeErr.setErrorSummary(ex.getMessage());
                routeErr.setOutputText(ex.getMessage());
                routeErr.setAttrsJson(ObsAttrs.withCause(ObsAttrs.withBrief(null, "路由失败"), CauseDigest.of(ex)));
                trace.close(routeSpan, routeErr);
            }
            throw ex;
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
    }

    /**
     * 固定模板执行（FIXED_PARALLEL / FIXED_SEQUENCE）。所属域：runtime。
     *
     * <p>与判路由路径的区别：<b>完全跳过判路由 LLM</b>，参与者＝全部启用候选（按 sort_order 升序，
     * 同序按 id），每个子的 sub_query 都是用户原问全文，不做意图切分。
     *
     * <p>观测：根 ROUTE 名称固定为 fixed-parallel / fixed-sequence；因为无判路由 LLM，
     * 子 AGENT 直接挂 ROUTE。并行子按同父扇出（N.1/N.2），串行子不扇出，泳道时间窗互不重叠。
     *
     * @param runtime       已解析的运行配置（模板与上下文策略从这里读）
     * @param parentLimits  父墙钟与轮次上限
     * @return 路由说明、分段与汇总后的终答
     */
    private RoutedTurn executeFixed(AgentDef parent, String userText, String modelUser, long userId, long sessionId,
                                    TrialProgress progress, AgentRuntimeConfig runtime, EffectiveLimits parentLimits) {
        boolean parallel = runtime.isFixedParallel();
        String template = runtime.maTemplateOrDefault();
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        Long routeSpan = trace == null ? null
                : trace.open(SpanType.ROUTE, parallel ? "fixed-parallel" : "fixed-sequence",
                parent.getId(), null, null);
        if (trace != null) {
            trace.bindParent(routeSpan);
        }
        AtomicBoolean routeClosed = new AtomicBoolean(false);
        try {
            assertParentDeadline(deadlineNanos);
            Candidates loaded = loadCandidates(parent.getId(), runtime);
            List<Candidate> enabled = loaded.usable();
            List<BlockedCandidate> blockedCandidates = loaded.blocked();
            // M4 精神：固定模板没有判路由兜底，零可用候选必须整次 FAIL，不能空跑出「无实质回答」
            if (enabled.isEmpty()) {
                String suffix = blockedCandidates.isEmpty() ? ""
                        : "（其中 " + blockedCandidates.size() + " 位专家未获本编排允许："
                                + blockedReasons(blockedCandidates) + "）";
                throw new TrialFailure((parallel
                        ? "固定并行没有可用的子 Agent：请至少启用 1 个获允许的子 Agent"
                        : "固定串行没有可用的子 Agent：请至少启用 1 个获允许的子 Agent") + suffix);
            }
            // 并行上限复用现网 maxRouteChildren（默认 3，硬顶 5）：超出的按序标 SKIPPED_QUOTA；串行不设上限
            int cap = runtime.maxRouteChildrenOrDefault();
            List<Candidate> picked = parallel && enabled.size() > cap
                    ? new ArrayList<>(enabled.subList(0, cap))
                    : enabled;
            List<RoutedIntent> intents = new ArrayList<>(picked.size());
            for (int i = 0; i < picked.size(); i++) {
                // sub_query 一律整段用户原问：固定模板不做意图切分，避免子只拿到半句话
                intents.add(new RoutedIntent(picked.get(i), userText, parallel ? "固定并行" : "固定串行",
                        fixedRouteLine(picked.get(i), parallel, i + 1, picked.size())));
            }
            List<RoutedIntent> skipped = new ArrayList<>();
            if (parallel && enabled.size() > cap) {
                for (int i = cap; i < enabled.size(); i++) {
                    Candidate leftover = enabled.get(i);
                    skipped.add(new RoutedIntent(leftover, userText, "超出并行上限",
                            "已跳过「" + childLabel(leftover.agent()) + "」：超出并行上限 " + cap));
                }
            }
            if (progress != null) {
                progress.route(planLine(parallel, intents.size(), skipped.size(), cap));
            }
            // 决策块仍写参与者 route_key + 用户原问：泳道据此给子块打 route_key，但与判路由结果无关
            String decisionJson = RouteDecisionParser.toDecisionJson(intents.stream()
                    .map(item -> new RouteDecisionParser.Item(
                            item.candidate().route().getRouteKey() == null ? ""
                                    : item.candidate().route().getRouteKey(),
                            item.subQuery()))
                    .toList());
            // 多子或 SUMMARIZE 时屏蔽子 assistant，等齐后再出终答（与判路由路径同口径）
            boolean mute = intents.size() > 1 || runtime.answerModeEnum() == RouterAnswerMode.SUMMARIZE;
            TrialProgress childProgress = mute ? muteAssistant(progress) : progress;
            List<ChildOutcome> outcomes = parallel
                    ? runChildren(parent, intents, modelUser, userText, userId, sessionId, deadlineNanos,
                    childProgress, trace, routeSpan,
                    // 多子强制隔离；单子按 maAllowParentHistoryWhenSingle 决定是否继承父历史
                    intents.size() >= 2 || !runtime.allowParentHistoryWhenSingleOn())
                    : runFixedSequence(parent, modelUser, userText, userId, sessionId, deadlineNanos,
                    childProgress, trace, routeSpan, intents, runtime);
            // 串行全失败：与单子失败口径一致，整次 FAIL，不拿占位句伪装成功
            if (!parallel && !outcomes.isEmpty()
                    && outcomes.stream().allMatch(outcome -> outcome.turn() == null)) {
                throw new TrialFailure("固定串行全部子 Agent 均未完成，请检查子 Agent 配置或稍后重试");
            }
            List<ChildOutcome> all = new ArrayList<>(outcomes);
            // 超出上限的候选仍要进汇总与事件，让用户看到「为什么它没跑」
            for (RoutedIntent skip : skipped) {
                all.add(ChildOutcome.skipped(skip,
                        "「" + childLabel(skip.candidate().agent()) + "」超出并行上限 " + cap + "，未执行"));
            }
            closeFixedRoute(trace, routeSpan, routeClosed, runtime, template, all, decisionJson, parallel);
            List<String> notices = new ArrayList<>();
            List<RouteSegment> segments = new ArrayList<>();
            for (ChildOutcome outcome : all) {
                notices.addAll(outcome.notices());
                // 跳过不是失败：只留「已跳过」路由行，不写成子 Agent 失败
                segments.add(new RouteSegment(outcome.intent().routeLine(), outcome.turn(),
                        outcome.skipped() ? null : outcome.error()));
            }
            /*
             * 未允许被编排调用的子：只追加路由行 + notice，不进 all，
             * 因此不参与终答拼接、不计 token，也不会触发额外的 assistant 推送。
             */
            for (ChildOutcome blocked : blockedOutcomes(blockedCandidates, userText)) {
                notices.addAll(blocked.notices());
                segments.add(new RouteSegment(blocked.intent().routeLine(), null, null));
            }
            TrialTurn turn = finishAnswer(parent, parentLimits, runtime, userText, all, intents,
                    userId, sessionId, deadlineNanos, progress, trace, routeSpan);
            List<String> lines = segments.stream().map(RouteSegment::routeLine).toList();
            return new RoutedTurn(String.join("；", lines), null, segments, notices, turn);
        } catch (RuntimeException ex) {
            log.warn("固定编排执行失败 parentId={} template={} type={} cause={}",
                    parent.getId(), template, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            if (trace != null && !routeClosed.get()) {
                SpanClose routeErr = new SpanClose();
                routeErr.setStatus(ObsStatus.ERROR);
                routeErr.setErrorSummary(ex.getMessage());
                routeErr.setOutputText(ex.getMessage());
                routeErr.setAttrsJson(ObsAttrs.withCause(
                        ObsAttrs.withBrief(null, parallel ? "固定并行失败" : "固定串行失败"), CauseDigest.of(ex)));
                trace.close(routeSpan, routeErr);
            }
            throw ex;
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
    }

    /**
     * 路由后串行执行（{@code ROUTER_THEN_SEQUENCE}）。所属域：runtime。
     *
     * <p>与判路由路径（{@link #execute(AgentDef, String, String, long, long, TrialProgress)}）
     * <b>共用同一份 JUDGE 契约</b>（同一份提示词与解析器），差别只在扇出方式：
     * 命中结果按<b>判定的数组顺序</b>串行执行，不看候选 sort_order。
     *
     * <p>关键纪律：
     * <ul>
     *   <li>判路由不可省：父模型缺失 / 调用失败 / 无有效 route_key 一律整次 FAIL；</li>
     *   <li>后一步输入＝本步子问 + 前置终答摘要（{@code PRIOR_SUMMARY_ONLY} 为默认），
     *       摘要硬顶复用 {@link FixedSequenceContext}（单步 2000 / 累计 6000 字符）；</li>
     *   <li>子步跑在 {@link #runFixedSequence} 上，因此「部分失败继续」与 P&E 单槽冒泡自动继承；</li>
     *   <li>判定条数超过 {@code maxRouteChildren} 时只跑判定序前 N，其余标 SKIPPED_QUOTA；</li>
     *   <li>汇总复用 CONCAT / SUMMARIZE（{@link #finishAnswer}），不另造汇总器。</li>
     * </ul>
     *
     * <p>obs：ROUTE 名为 {@code router-then-sequence}，attrs 写模板 / 汇总 / 上下文三项，
     * <b>不写</b> {@code ma.skip_judge}——本模板确实调用了判路由 LLM。
     *
     * @param parent       已启用的 ROUTER
     * @param userText     用户原问全文（判路由输入，也是各步摘要区的整体目标）
     * @param modelUser    已拼好记忆/近轮的用户侧文本（单步可继承父历史时使用）
     * @param runtime      已解析的运行配置（模板、上下文策略、汇总、上限从这里读）
     * @param parentLimits 父墙钟与轮次上限
     * @return 各步分段 + 汇总后的终答
     */
    private RoutedTurn executeRouterThenSequence(AgentDef parent, String userText, String modelUser,
                                                 long userId, long sessionId, TrialProgress progress,
                                                 AgentRuntimeConfig runtime, EffectiveLimits parentLimits) {
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        Long routeSpan = trace == null ? null
                : trace.open(SpanType.ROUTE, "router-then-sequence", parent.getId(), null, null);
        if (trace != null) {
            trace.bindParent(routeSpan);
        }
        AtomicBoolean routeClosed = new AtomicBoolean(false);
        try {
            assertParentDeadline(deadlineNanos);
            Candidates loaded = loadCandidates(parent.getId(), runtime);
            List<Candidate> candidates = loaded.usable();
            // M4：本模板先判路由再串行，判路由本身没有「降级兜底」，零可用候选必须整次 FAIL
            if (candidates.isEmpty()) {
                throw new TrialFailure(loaded.blocked().isEmpty()
                        ? "路由后串行没有可用的子 Agent：请至少启用 1 个获允许的子 Agent"
                        : "路由后串行没有可用的子 Agent：" + loaded.blocked().size()
                                + " 位专家未获本编排允许（" + blockedReasons(loaded.blocked())
                                + "），请检查编排策略与对应 Agent 配置");
            }
            // 1) JUDGE：严格版判路由，失败即 FAIL，绝不拿第一条候选顶上
            List<RoutedIntent> judged = chooseOrdered(parent, parentLimits, runtime, userText, candidates,
                    userId, sessionId, deadlineNanos);
            // 2) 上限：串行不扇出，超出配置上限的按判定序截断并标「已跳过」
            int cap = runtime.maxRouteChildrenOrDefault();
            List<RoutedIntent> picked = judged.size() > cap
                    ? new ArrayList<>(judged.subList(0, cap))
                    : judged;
            List<RoutedIntent> intents = new ArrayList<>(picked.size());
            for (int i = 0; i < picked.size(); i++) {
                RoutedIntent base = picked.get(i);
                // 标题在派发前定稿：串行时时间线顺序＝执行顺序，序号就是判定序
                intents.add(new RoutedIntent(base.candidate(), base.subQuery(), base.reason(),
                        serialRouteLine("路由后串行 ", base.candidate(), i + 1, picked.size())));
            }
            List<RoutedIntent> skipped = new ArrayList<>();
            for (int i = cap; i < judged.size(); i++) {
                RoutedIntent leftover = judged.get(i);
                skipped.add(new RoutedIntent(leftover.candidate(), leftover.subQuery(), "超出串行上限",
                        "已跳过「" + childLabel(leftover.candidate().agent()) + "」：超出串行上限 " + cap));
            }
            if (progress != null) {
                progress.route(sequencePlanLine(intents.size(), skipped.size(), cap, runtime.priorSummaryOn()));
            }
            // 决策块只写真正要执行的步骤：泳道据此给子块打 route_key，与「跳过」区分开
            String decisionJson = RouteDecisionParser.toDecisionJson(intents.stream()
                    .map(item -> new RouteDecisionParser.Item(
                            item.candidate().route().getRouteKey() == null ? ""
                                    : item.candidate().route().getRouteKey(),
                            item.subQuery()))
                    .toList());
            if (progress != null && intents.size() >= 2) {
                progress.route("路由决策\n" + decisionJson);
            }
            // 3) 串行 FANOUT：复用固定串行实现，前置摘要 / 部分失败继续 / P&E 冒泡一并继承
            boolean mute = intents.size() > 1 || runtime.answerModeEnum() == RouterAnswerMode.SUMMARIZE;
            TrialProgress childProgress = mute ? muteAssistant(progress) : progress;
            List<ChildOutcome> outcomes = runFixedSequence(parent, modelUser, userText, userId, sessionId,
                    deadlineNanos, childProgress, trace, childAgentParent(trace, routeSpan), intents, runtime);
            // 所有执行步都没产出正文：整次 FAIL，不拿占位段假装成功
            if (!outcomes.isEmpty() && outcomes.stream().allMatch(outcome -> outcome.turn() == null)) {
                throw new TrialFailure("路由后串行全部子 Agent 均未完成，请检查子 Agent 配置或稍后重试");
            }
            List<ChildOutcome> all = new ArrayList<>(outcomes);
            for (RoutedIntent skip : skipped) {
                all.add(ChildOutcome.skipped(skip,
                        "「" + childLabel(skip.candidate().agent()) + "」超出串行上限 " + cap + "，未执行"));
            }
            closeRouterThenSequenceRoute(trace, routeSpan, routeClosed, runtime, all, decisionJson);
            List<String> notices = new ArrayList<>();
            List<RouteSegment> segments = new ArrayList<>();
            for (ChildOutcome outcome : all) {
                notices.addAll(outcome.notices());
                // 跳过不是失败：只留「已跳过」行，不写成子 Agent 失败
                segments.add(new RouteSegment(outcome.intent().routeLine(), outcome.turn(),
                        outcome.skipped() ? null : outcome.error()));
            }
            /*
             * 未获允许的子不参与判路由，但必须在时间线留「已跳过」：
             * 只写 routeLine + notice，不塞进 all，避免改变终答口径。
             */
            for (ChildOutcome blocked : blockedOutcomes(loaded.blocked(), userText)) {
                notices.addAll(blocked.notices());
                segments.add(new RouteSegment(blocked.intent().routeLine(), null, null));
            }
            // 4) AGGREGATE：串行结束后再汇总，CONCAT / SUMMARIZE 与现网同口径
            TrialTurn turn = finishAnswer(parent, parentLimits, runtime, userText, all, intents,
                    userId, sessionId, deadlineNanos, progress, trace, routeSpan);
            List<String> lines = segments.stream().map(RouteSegment::routeLine).toList();
            return new RoutedTurn(String.join("；", lines), null, segments, notices, turn);
        } catch (RuntimeException ex) {
            log.warn("路由后串行执行失败 parentId={} type={} cause={}",
                    parent.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            if (trace != null && !routeClosed.get()) {
                SpanClose routeErr = new SpanClose();
                routeErr.setStatus(ObsStatus.ERROR);
                routeErr.setErrorSummary(ex.getMessage());
                routeErr.setOutputText(ex.getMessage());
                routeErr.setAttrsJson(ObsAttrs.withCause(
                        ObsAttrs.withBrief(null, "路由后串行失败"), CauseDigest.of(ex)));
                trace.close(routeSpan, routeErr);
            }
            throw ex;
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
    }

    /**
     * 评审环执行（{@code CRITIC_LOOP}）。所属域：runtime。
     *
     * <p>拓扑固定为 <b>执行者 → 评审者 →（REVISE 时）执行者回修</b>，全部串行：
     * <ul>
     *   <li>跳过判路由 LLM（不写 ROUTE.route_decision）；</li>
     *   <li>执行者＝启用候选中恰好 1 个（评审者若也在候选表里，按 id 排除，不计入个数）；</li>
     *   <li>轮次默认 1（已夹到 1～3）：1 轮＝一次评审判定 + 至多一次回修；
     *       {@code maxRounds=1} 时回修后直接终稿，不再二次评审；</li>
     *   <li>终答＝最后一版执行者输出，不跑 SUMMARIZE / CONCAT 拼接；</li>
     *   <li>评审判定解析失败按 ACCEPT 处理（防死循环），obs 记 {@code ma.critic_parse=fail}；</li>
     *   <li>评审者失败降级采用当前稿；回修失败降级采用回修前稿（都带中文可见说明）。</li>
     * </ul>
     *
     * <p>批准冒泡：执行者若为 P&E 且自身开启「规划确认」，仍走刀4 的单槽冒泡，
     * 未获批前不会进入评审；评审环本身不产生新的待批状态。
     *
     * @param parent       已启用的 ROUTER
     * @param userText     用户原问全文；本模板恒按隔离上下文派发（不继承父历史）
     * @param progress     可空；评审/回修阶段的 assistant 增量被静音，终答等齐后只推一次
     * @param runtime      已解析的运行配置（模板、评审者、轮次从这里读）
     * @param parentLimits 父墙钟与轮次上限
     * @return 终答＝最后一版执行者输出，segments 保留每阶段标题与真实 spans
     */
    private RoutedTurn executeCriticLoop(AgentDef parent, String userText, long userId, long sessionId,
                                         TrialProgress progress, AgentRuntimeConfig runtime,
                                         EffectiveLimits parentLimits) {
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        Long routeSpan = trace == null ? null
                : trace.open(SpanType.ROUTE, "critic-loop", parent.getId(), null, null);
        if (trace != null) {
            trace.bindParent(routeSpan);
        }
        AtomicBoolean routeClosed = new AtomicBoolean(false);
        // 评审环的所有阶段都静音 assistant：初稿若直接流出，REVISE 后会变成「两版答复」拼在时间线上
        TrialProgress stageProgress = muteAssistant(progress);
        List<RouteSegment> segments = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        List<ChildOutcome> stages = new ArrayList<>();
        int maxRounds = runtime.criticMaxRoundsOrDefault();
        int roundsUsed = 0;
        String decision = CriticVerdictParser.DECISION_ACCEPT;
        boolean parseFailed = false;
        String degradeNote = null;
        try {
            assertParentDeadline(deadlineNanos);
            Candidates loaded = loadCandidates(parent.getId(), runtime);
            Long criticId = runtime.getMaCriticAgentId();
            Candidate executor = pickCriticExecutor(loaded, criticId);
            AgentDef criticAgent = pickCriticAgent(runtime, criticId, executor);
            Candidate critic = new Candidate(criticRoute(criticAgent), criticAgent);

            String planLine = "评审环：跳过判路由，执行者出稿 → 评审最多 " + maxRounds + " 轮（不满意则回修）";
            pushLine(progress, segments, planLine);

            // 初稿：用户原问全文直发执行者；隔离上下文，单子也不继承父历史
            String draftLine = "评审环 · 执行者出稿";
            pushLine(progress, segments, draftLine);
            ChildOutcome draft = runCriticStage(parent, executor, "评审环 · 执行者出稿", userText, userText,
                    userId, sessionId, deadlineNanos, stageProgress, trace, routeSpan, "executor", 0);
            segments.add(new RouteSegment(draftLine, draft.turn(), null));
            notices.addAll(draft.notices());
            stages.add(draft);
            TrialTurn current = draft.turn();
            if (current == null) {
                throw new TrialFailure("评审环执行者未产出稿件，无法评审");
            }

            for (int round = 1; round <= maxRounds; round++) {
                assertParentDeadline(deadlineNanos);
                String reviewLine = "评审环 · 评审（第 " + round + " 轮）";
                pushLine(progress, segments, reviewLine);
                String reviewInput = CriticPrompt.review(userText, current.getAssistantText());
                ChildOutcome reviewed;
                try {
                    reviewed = runCriticStage(parent, critic, "评审环 · 评审", reviewInput, reviewInput,
                            userId, sessionId, deadlineNanos, stageProgress, trace, routeSpan, "critic", round);
                } catch (TrialFailure ex) {
                    // 墙钟/根取消属于整次失败，不做降级；其它评审失败采用当前稿
                    if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                        throw ex;
                    }
                    log.warn("评审环评审失败 parentId={} round={} type={} cause={}",
                            parent.getId(), round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    degradeNote = "评审未完成，已返回执行稿";
                    segments.add(new RouteSegment(reviewLine + " · 未完成", null, null));
                    break;
                } catch (RuntimeException ex) {
                    log.warn("评审环评审异常 parentId={} round={} type={} cause={}",
                            parent.getId(), round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    degradeNote = "评审未完成，已返回执行稿";
                    segments.add(new RouteSegment(reviewLine + " · 未完成", null, null));
                    break;
                }
                segments.add(new RouteSegment(reviewLine, reviewed.turn(), null));
                stages.add(reviewed);
                CriticVerdictParser.Verdict verdict = CriticVerdictParser.parse(
                        reviewed.turn() == null ? null : reviewed.turn().getAssistantText());
                if (!verdict.parsed()) {
                    parseFailed = true;
                }
                decision = verdict.decision();
                roundsUsed = round;
                String verdictLine = "评审环 · 第 " + round + " 轮结论："
                        + criticDecisionText(verdict.decision())
                        + (verdict.parsed() ? "" : "（评审输出无法解析，按通过处理）");
                pushLine(progress, segments, verdictLine);
                if (!verdict.revise()) {
                    break;
                }
                String reviseLine = "评审环 · 回修（第 " + round + " 轮）";
                pushLine(progress, segments, reviseLine);
                String reviseInput = CriticPrompt.revise(userText, current.getAssistantText(),
                        verdict.feedbackOrEmpty());
                ChildOutcome revised;
                try {
                    revised = runCriticStage(parent, executor, "评审环 · 回修", reviseInput, reviseInput,
                            userId, sessionId, deadlineNanos, stageProgress, trace, routeSpan, "executor", round);
                } catch (TrialFailure ex) {
                    if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                        throw ex;
                    }
                    log.warn("评审环回修失败 parentId={} round={} type={} cause={}",
                            parent.getId(), round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    degradeNote = "回修未完成，已返回上一版执行稿";
                    segments.add(new RouteSegment(reviseLine + " · 未完成", null, null));
                    break;
                } catch (RuntimeException ex) {
                    log.warn("评审环回修异常 parentId={} round={} type={} cause={}",
                            parent.getId(), round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    degradeNote = "回修未完成，已返回上一版执行稿";
                    segments.add(new RouteSegment(reviseLine + " · 未完成", null, null));
                    break;
                }
                segments.add(new RouteSegment(reviseLine, revised.turn(), null));
                notices.addAll(revised.notices());
                stages.add(revised);
                if (revised.turn() == null) {
                    degradeNote = "回修未产出新稿，已返回上一版执行稿";
                    break;
                }
                current = revised.turn();
                // 轮次用尽：回修稿直接作为终稿，不再二次评审（P1 默认 1 轮即此路径）。
                // 单轮是正常路径而不是「超限」，不加降级说明；多轮用尽才提示已到上限
                if (round >= maxRounds && maxRounds > 1) {
                    degradeNote = "已达评审轮次上限（" + maxRounds + " 轮），已采用最后一版执行稿";
                }
            }

            String finalText = current.getAssistantText() == null ? "" : current.getAssistantText();
            if (degradeNote != null && !degradeNote.isBlank()) {
                // 降级说明必须可见：用户要知道这是「执行稿」而不是「评审通过的终稿」
                finalText = finalText + "\n\n（" + degradeNote + "）";
                pushLine(progress, segments, degradeNote);
            }
            TrialTurn turn = mergeTurns(stages, finalText);
            if (progress != null) {
                progress.assistant(turn.getAssistantText());
            }
            closeCriticRoute(trace, routeSpan, routeClosed, runtime, roundsUsed, decision, parseFailed);
            /*
             * 未获允许/被挡下的候选：只追加「已跳过」路由行，不进 stages，
             * 因此不参与终答、不计 token，也不会触发额外 assistant 推送。
             */
            for (ChildOutcome blocked : blockedOutcomes(loaded.blocked(), userText)) {
                notices.addAll(blocked.notices());
                segments.add(new RouteSegment(blocked.intent().routeLine(), null, null));
            }
            String summary = "评审环：" + roundsUsed + " 轮 · " + criticDecisionText(decision);
            return new RoutedTurn(summary, null, segments, notices, turn);
        } catch (RuntimeException ex) {
            log.warn("评审环执行失败 parentId={} type={} cause={}",
                    parent.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            if (trace != null && !routeClosed.get()) {
                SpanClose routeErr = new SpanClose();
                routeErr.setStatus(ObsStatus.ERROR);
                routeErr.setErrorSummary(ex.getMessage());
                routeErr.setOutputText(ex.getMessage());
                routeErr.setAttrsJson(ObsAttrs.withCause(ObsAttrs.withBrief(null, "评审环失败"), CauseDigest.of(ex)));
                trace.close(routeSpan, routeErr);
            }
            throw ex;
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
    }

    /**
     * 解析评审环的执行者：启用可用候选里排除评审者后，必须恰好 1 个。
     *
     * @param loaded    候选装载结果（usable 已含 allow_orchestrated / 子模式门闩）
     * @param criticId  评审者 id；可空（未选时无法排除，也不影响下面的个数校验）
     * @return 唯一执行者
     * @throws TrialFailure 0 个或多于 1 个
     */
    private Candidate pickCriticExecutor(Candidates loaded, Long criticId) {
        List<Candidate> executors = new ArrayList<>();
        for (Candidate candidate : loaded.usable()) {
            if (criticId != null && criticId.equals(candidate.agent().getId())) {
                continue;
            }
            executors.add(candidate);
        }
        // 稳定排序：sort_order 升序、同序 id 升序；恰好 1 个时顺序无影响，多于 1 个时用于报错展示
        executors.sort(Comparator
                .comparing((Candidate item) -> item.route().getSortOrder(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(item -> item.route().getId(), Comparator.nullsFirst(Comparator.naturalOrder())));
        if (executors.isEmpty()) {
            // 评审者恰好就是唯一候选：这不是「没有执行者」，而是自评，要给能直接改配置的文案
            boolean criticIsOnlyCandidate = criticId != null && loaded.usable().stream()
                    .anyMatch(item -> criticId.equals(item.agent().getId()));
            if (criticIsOnlyCandidate) {
                throw new TrialFailure("评审专家不能与执行者相同：请换一个评审专家，或把候选换成别的执行者");
            }
            String suffix = loaded.blocked().isEmpty() ? ""
                    : "（其中 " + loaded.blocked().size() + " 位专家未获本编排允许：" + blockedReasons(loaded.blocked()) + "）";
            throw new TrialFailure("评审环需要恰好 1 个启用中的执行者候选，当前 0 个" + suffix
                    + "：候选需已启用、运行模式获本编排允许且已开启「允许被编排调用」");
        }
        if (executors.size() > 1) {
            List<String> names = executors.stream().map(item -> childLabel(item.agent())).toList();
            throw new TrialFailure("评审环只允许 1 个执行者候选，当前 " + executors.size() + " 个（"
                    + String.join("、", names) + "）：请停用多余候选，只保留 1 个执行者");
        }
        return executors.get(0);
    }

    /**
     * 解析评审者：必填、启用、非 ROUTER、运行模式获本编排允许、允许被编排调用，且不等于执行者。
     *
     * @param runtime  编排根运行配置
     * @param criticId 评审者 id；可空
     * @param executor 执行者候选
     * @return 评审者定义
     * @throws TrialFailure 未选/不存在/不合法/与执行者相同
     */
    private AgentDef pickCriticAgent(AgentRuntimeConfig runtime, Long criticId, Candidate executor) {
        if (criticId == null) {
            throw new TrialFailure("评审环未选择评审专家：请在「编排」Tab 的评审专家下拉中选择 1 个已启用的子 Agent");
        }
        AgentDef critic = agents.findById(criticId).orElse(null);
        // 与保存侧共用同一份中文原因，避免「保存提示」与「试跑报错」各说一套
        String problem = AgentCriticPolicy.criticProblem(runtime, critic);
        if (problem != null) {
            throw new TrialFailure(problem);
        }
        if (executor.agent().getId() != null && executor.agent().getId().equals(critic.getId())) {
            throw new TrialFailure("评审专家不能与执行者相同：请换一个评审专家，或把候选换成别的执行者");
        }
        return critic;
    }

    /**
     * 跑一个评审环阶段（执行者出稿 / 评审 / 回修）。全部串行，挂在根 ROUTE 下。
     *
     * @param role     角色：executor / critic；只用于 obs attrs 与泳道展示
     * @param round    轮次；初稿为 0
     * @param prompt   本次送给子 Agent 的用户侧全文
     * @param subQuery span 展示用的子问（与 prompt 同源，避免把评审契约当成用户问题展示）
     * @return 子执行结果
     */
    private ChildOutcome runCriticStage(AgentDef parent, Candidate hit, String roleText, String prompt,
                                        String subQuery, long userId, long sessionId, long deadlineNanos,
                                        TrialProgress progress, ObsTraceScope trace, Long routeSpan,
                                        String role, int round) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("ma.role", role);
        attrs.put("ma.critic_round", round);
        RoutedIntent intent = new RoutedIntent(hit, subQuery, roleText, null);
        return runOneChild(parent, intent, prompt, subQuery, userId, sessionId, deadlineNanos, progress, trace,
                routeSpan, false, true, Thread.currentThread(), prompt, attrs);
    }

    /**
     * 评审者的合成路由行：评审专家不必出现在候选表，运行期只需一个携带 intent 的占位行。
     *
     * @param critic 评审者
     * @return 仅用于 span/展示的 AgentRoute
     */
    private static AgentRoute criticRoute(AgentDef critic) {
        AgentRoute route = new AgentRoute();
        route.setChildAgentId(critic.getId());
        route.setRouteKey("");
        route.setIntent("评审专家");
        route.setSortOrder(0);
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    /**
     * 同时推进「实时事件」与「落库分段」：两条口径必须一致，否则刷新后时间线会少一段。
     */
    private static void pushLine(TrialProgress progress, List<RouteSegment> segments, String line) {
        if (progress != null) {
            progress.route(line);
        }
        if (line != null && !line.isBlank()) {
            segments.add(new RouteSegment(line, null, null));
        }
    }

    /**
     * @param decision ACCEPT / REVISE
     * @return 中文结论（带英文枚举，便于对照日志）
     */
    private static String criticDecisionText(String decision) {
        return CriticVerdictParser.DECISION_REVISE.equals(decision)
                ? "需要回修（REVISE）"
                : "评审通过（ACCEPT）";
    }

    /**
     * 关闭评审环的根 ROUTE：写模板、跳过判路由、实际轮次与判定。
     */
    private void closeCriticRoute(ObsTraceScope trace, Long routeSpan, AtomicBoolean closed,
                                  AgentRuntimeConfig runtime, int roundsUsed, String decision,
                                  boolean parseFailed) {
        if (trace == null || routeSpan == null) {
            return;
        }
        SpanClose close = new SpanClose();
        close.setStatus(ObsStatus.OK);
        close.setOutputText("评审环完成：" + criticDecisionText(decision) + "，评审 " + roundsUsed + " 轮");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ma.template", runtime.maTemplateOrDefault());
        // 评审环没有判路由 LLM：显式打点，泳道据此展示「无判路由」而不是假 JUDGE
        fields.put("ma.skip_judge", "true");
        fields.put("ma.critic_rounds_used", roundsUsed);
        fields.put("ma.critic_decision", decision);
        if (parseFailed) {
            fields.put("ma.critic_parse", "fail");
        }
        // 评审环若选了共享草稿：执行者与评审者共享同一块板，统计只写次数不写正文
        fields.putAll(scratchAttrs());
        close.setAttrsJson(ObsAttrs.withFields(
                ObsAttrs.withBrief(null, "评审环×" + roundsUsed + " 轮 · " + criticDecisionText(decision)),
                fields));
        trace.close(routeSpan, close);
        closed.set(true);
    }

    /**
     * 串行：按 intents 顺序依次执行，前一步的终答摘要进下一步输入；单步失败默认继续后续步。
     * <p>固定串行与路由后串行共用本方法：两者的差别只是 intents 的来源
     * （候选 sort_order vs 判路由数组序）与 sub_query（整段原问 vs 判路由切分）。
     *
     * @param intents 已按执行顺序排好的参与者
     * @param runtime 运行配置（决定是否拼前置摘要、单子是否继承父历史）
     * @return 每步结果，顺序与 intents 一致；失败步 turn 为空
     */
    private List<ChildOutcome> runFixedSequence(AgentDef parent, String modelUser, String userText, long userId,
                                                long sessionId, long deadlineNanos, TrialProgress progress,
                                                ObsTraceScope trace, Long childParent, List<RoutedIntent> intents,
                                                AgentRuntimeConfig runtime) {
        List<ChildOutcome> outcomes = new ArrayList<>(intents.size());
        List<FixedSequenceContext.PriorStep> prior = new ArrayList<>();
        boolean priorSummary = runtime.priorSummaryOn();
        // 单子串行没有「前置」，与现网单意图对齐：允许继承父历史，否则硬隔离
        boolean single = intents.size() == 1;
        for (RoutedIntent intent : intents) {
            assertParentDeadline(deadlineNanos);
            // 每步先推本步分块标题；串行下后续 reasoning/tool 自然落进该块，不需要 lane 重推
            if (progress != null && intent.routeLine() != null) {
                progress.route(intent.routeLine());
            }
            // 多步串行默认只给「本步子问 + 前置终答摘要」，绝不灌父历史/工具原文；单步交回既有解析
            String stepModelText = null;
            boolean isolate = false;
            if (!single) {
                /*
                 * 本步的问题基座取 intent.subQuery()：
                 * - 固定串行里 sub_query 恒等于用户原问（不做切分）；
                 * - 路由后串行里它是判路由切给该子的子问题，不能再拿整段合问。
                 */
                String stepQuestion = intent.subQuery() == null || intent.subQuery().isBlank()
                        ? userText
                        : intent.subQuery();
                stepModelText = priorSummary ? FixedSequenceContext.render(stepQuestion, prior) : stepQuestion;
                isolate = true;
            } else {
                isolate = !runtime.allowParentHistoryWhenSingleOn();
            }
            ChildOutcome outcome;
            try {
                outcome = runOneChild(parent, intent, modelUser, userText, userId, sessionId, deadlineNanos,
                        progress, trace, childParent, false, isolate, Thread.currentThread(), stepModelText);
            } catch (RuntimeException ex) {
                // 默认「失败继续」：压成占位段，后续步仍能看到「该步未完成」而不是整条序列中断
                log.warn("固定串行第 {} 步失败 agentId={} type={} cause={}", outcomes.size() + 1,
                        parent.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                outcome = ChildOutcome.failed(intent, UserFacingText.safeMessage(ex, "子 Agent 执行失败"));
            }
            outcomes.add(outcome);
            // 只把终答正文进摘要区：工具原文/思考过程不外泄给下一步
            prior.add(new FixedSequenceContext.PriorStep(intent.routeLine(),
                    outcome.turn() == null ? null : outcome.turn().getAssistantText(),
                    outcome.turn() == null));
        }
        return outcomes;
    }

    /**
     * 关闭固定模板的根 ROUTE：写实际模板 / 汇总策略 / 上下文策略，并附跳过与失败计数。
     */
    private void closeFixedRoute(ObsTraceScope trace, Long routeSpan, AtomicBoolean closed,
                                 AgentRuntimeConfig runtime, String template, List<ChildOutcome> outcomes,
                                 String decisionJson, boolean parallel) {
        if (trace == null || routeSpan == null) {
            return;
        }
        int ok = 0;
        int fail = 0;
        int skipped = 0;
        for (ChildOutcome outcome : outcomes) {
            if (outcome.skipped()) {
                skipped++;
            } else if (outcome.error() != null) {
                fail++;
            } else {
                ok++;
            }
        }
        SpanClose close = new SpanClose();
        close.setStatus(ObsStatus.OK);
        close.setOutputText((parallel ? "固定并行完成 " : "固定串行完成 ") + ok + " 子");
        close.setRouteDecision(decisionJson);
        StringBuilder brief = new StringBuilder(parallel ? "固定并行×" : "固定串行×").append(outcomes.size());
        if (fail > 0) {
            brief.append("（失败 ").append(fail).append("）");
        }
        if (skipped > 0) {
            brief.append("（跳过 ").append(skipped).append("）");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ma.template", template);
        fields.put("ma.aggregate", runtime.answerModeEnum().name());
        fields.put("ma.context", runtime.maContextPolicyOrDefault());
        // 固定模板恒无判路由 LLM；显式打点，泳道据此显示「无判路由」
        fields.put("ma.skip_judge", "true");
        // 共享草稿只写键数 / 冲突次数（有则显示），不写任何 key / value 正文
        fields.putAll(scratchAttrs());
        close.setAttrsJson(ObsAttrs.withFields(ObsAttrs.withBrief(null, brief.toString()), fields));
        trace.close(routeSpan, close);
        closed.set(true);
    }

    /**
     * 固定模板的子块标题：与判路由的「路由到…」区分，避免让人误以为走了意图判定。
     */
    private String fixedRouteLine(Candidate hit, boolean parallel, int index, int total) {
        return serialRouteLine(parallel ? "固定并行 " : "固定串行 ", hit, index, total);
    }

    /**
     * 串行模板（固定串行 / 路由后串行）的子块标题。
     *
     * <p>序号即执行顺序：路由后串行里它是「判定序」，不是候选 sort_order，
     * 泳道与时间线据此对齐，避免用户拿候选顺序去对照结果。
     *
     * @param prefix 模板前缀（含结尾空格），如「固定串行 」
     * @param hit    命中的候选
     * @param index  1 起的执行序号
     * @param total  本次执行步数
     * @return 如「路由后串行 1/3 · 售后专家（after_sale）」
     */
    private String serialRouteLine(String prefix, Candidate hit, int index, int total) {
        StringBuilder line = new StringBuilder(prefix);
        line.append(index).append('/').append(total).append(" · ").append(childLabel(hit.agent()));
        String key = hit.route().getRouteKey() == null ? "" : hit.route().getRouteKey().trim();
        if (!key.isEmpty()) {
            line.append("（").append(key).append("）");
        }
        return line.toString();
    }

    /**
     * 路由后串行的总控说明：说明「判路由 → 串行」、上下文口径与被跳过的原因。
     *
     * @param picked       实际执行步数
     * @param skipped      超出上限被跳过的步数
     * @param cap          配置的串行上限（复用 maxRouteChildren）
     * @param priorSummary 是否按前置摘要拼后步输入
     * @return 中文说明
     */
    private static String sequencePlanLine(int picked, int skipped, int cap, boolean priorSummary) {
        StringBuilder line = new StringBuilder("路由后串行：判路由命中 ").append(picked)
                .append(" 个子，按判定顺序串行执行");
        line.append(priorSummary ? "（后步只看前置终答摘要）" : "（上下文隔离，后步看不到前置正文）");
        if (skipped > 0) {
            line.append("；另 ").append(skipped).append(" 个超出串行上限 ").append(cap).append(" 已跳过");
        }
        return line.toString();
    }

    /**
     * 固定模板的总控说明：让用户一眼看到「跳过判路由」以及被跳过的原因。
     */
    private static String planLine(boolean parallel, int picked, int skipped, int cap) {
        StringBuilder line = new StringBuilder(parallel
                ? "固定并行：跳过判路由，直接派给 " + picked + " 个子"
                : "固定串行：跳过判路由，按顺序执行 " + picked + " 个子");
        if (skipped > 0) {
            line.append("；另 ").append(skipped).append(" 个超出并行上限 ").append(cap).append(" 已跳过");
        }
        return line.toString();
    }

    /**
     * 关闭路由后串行的根 ROUTE：写实际模板 / 汇总策略 / 上下文策略与失败跳过计数。
     *
     * <p>与固定模板的关键差别：本模板<b>确实调用了</b>判路由 LLM，因此绝不写
     * {@code ma.skip_judge}；泳道据模板码区分「JUDGE 后串行」与「无判路由」。
     */
    private void closeRouterThenSequenceRoute(ObsTraceScope trace, Long routeSpan, AtomicBoolean closed,
                                              AgentRuntimeConfig runtime, List<ChildOutcome> outcomes,
                                              String decisionJson) {
        if (trace == null || routeSpan == null) {
            return;
        }
        int ok = 0;
        int fail = 0;
        int skipped = 0;
        for (ChildOutcome outcome : outcomes) {
            if (outcome.skipped()) {
                skipped++;
            } else if (outcome.error() != null) {
                fail++;
            } else {
                ok++;
            }
        }
        SpanClose close = new SpanClose();
        close.setStatus(ObsStatus.OK);
        close.setOutputText("路由后串行完成 " + ok + " 子");
        close.setRouteDecision(decisionJson);
        StringBuilder brief = new StringBuilder("路由后串行×").append(outcomes.size());
        if (fail > 0) {
            brief.append("（失败 ").append(fail).append("）");
        }
        if (skipped > 0) {
            brief.append("（跳过 ").append(skipped).append("）");
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("ma.template", runtime.maTemplateOrDefault());
        attrs.put("ma.aggregate", runtime.answerModeEnum().name());
        attrs.put("ma.context", runtime.maContextPolicyOrDefault());
        // 共享草稿只写键数 / 冲突次数（有则显示），不写任何 key / value 正文
        attrs.putAll(scratchAttrs());
        close.setAttrsJson(ObsAttrs.withFields(ObsAttrs.withBrief(null, brief.toString()), attrs));
        trace.close(routeSpan, close);
        closed.set(true);
    }

    /**
     * ≥2 并行；=1 与现网一致。失败子写入 outcome.error，不打断其它子。
     * <p>
     * 线程来源：全局 {@code routerTaskExecutor}（有界队列 + CallerRuns），本方法<b>不</b>再新建或关闭线程池；
     * 池满时 CallerRuns 会把子任务退回当前请求线程执行，因此本方法在极端压力下仍可能串行完成。
     *
     * @param childParent  子 AGENT 的 parent_id（判路由 LLM，或无 LLM 时的 ROUTE）
     * @param isolateChild 多意图时 true：子仅吃 sub_query
     */
    private List<ChildOutcome> runChildren(AgentDef parent, List<RoutedIntent> intents, String modelUser,
                                           String userText, long userId, long sessionId, long deadlineNanos,
                                           TrialProgress progress, ObsTraceScope trace, Long childParent,
                                           boolean isolateChild) {
        if (intents.size() == 1) {
            // 单意图内联执行，不经过线程池；submitter 传当前线程即可（清理分支本就不会走到）
            return List.of(runOneChild(parent, intents.get(0), modelUser, userText, userId, sessionId,
                    deadlineNanos, progress, trace, childParent, false, isolateChild, Thread.currentThread(), null));
        }
        List<Future<ChildOutcome>> futures = new ArrayList<>(intents.size());
        // 并行子跑池线程：ThreadLocal 不跨线程继承，必须由提交线程把同一块草稿板带进 worker
        ScratchBoard scratch = ScratchContext.board();
        try {
            Thread submitter = Thread.currentThread();
            for (RoutedIntent intent : intents) {
                futures.add(routerTaskExecutor.submit(
                        () -> ScratchContext.callAs(scratch, scratchOwner(intent), () ->
                                runOneChild(parent, intent, modelUser, userText, userId, sessionId,
                                        deadlineNanos, progress, trace, childParent, true, isolateChild,
                                        submitter, null))));
            }
            List<ChildOutcome> outcomes = new ArrayList<>(intents.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    assertParentDeadline(deadlineNanos);
                    long remainMs = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                    outcomes.add(futures.get(i).get(remainMs, TimeUnit.MILLISECONDS));
                } catch (TrialFailure ex) {
                    // 墙钟超时：尽量打断未完成子
                    cancelPending(futures);
                    throw ex;
                } catch (InterruptedException ex) {
                    // 请求线程被中断：先恢复中断标记再按失败收束，避免吞掉停机/取消信号
                    Thread.currentThread().interrupt();
                    cancelPending(futures);
                    outcomes.add(ChildOutcome.failed(intents.get(i), "子 Agent 执行被中断"));
                } catch (Exception ex) {
                    cancelPending(futures);
                    // ExecutionException 需剥掉包装层才能拿到业务异常；中断/超时按原样处理
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    // 包装层会吞掉原始栈，这里带 Throwable 打一次，保证并行子失败也有全栈
                    log.warn("并行子 Agent 执行失败 sessionId={} index={} type={} cause={}",
                            sessionId, i, cause.getClass().getSimpleName(), CauseDigest.of(cause), cause);
                    // 失败段正文会进终答拼接（用户可见）：统一走边界清洗，不给路径/端口/异常码
                    outcomes.add(ChildOutcome.failed(intents.get(i),
                            UserFacingText.safeMessage(cause, "子 Agent 执行失败")));
                }
            }
            return outcomes;
        } finally {
            // 只取消本方法提交的任务，池本身归容器所有，禁止 shutdown
            cancelPending(futures);
        }
    }

    private void cancelPending(List<Future<ChildOutcome>> futures) {
        for (Future<ChildOutcome> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    /**
     * 子 AGENT 挂点：优先本 trace 最近判路由 LLM；无则退 ROUTE。禁止 null/根。
     *
     * @param trace     当前埋点；可空
     * @param routeSpan ROUTE 行 id；可空
     * @return 非根挂点；trace/route 皆空时为 null
     */
    private static Long childAgentParent(ObsTraceScope trace, Long routeSpan) {
        if (trace != null) {
            Long judgeLlm = trace.lastLlmSpanId();
            if (judgeLlm != null) {
                return judgeLlm;
            }
        }
        return routeSpan;
    }

    /**
     * 跑单个子 Agent。
     * <p>
     * 线程约定：正常情况运行在 {@code routerTaskExecutor} 的池线程上；池满触发 CallerRuns 时会
     * <b>内联跑在提交线程</b>（试跑请求线程）。两种情形对 ThreadLocal 的处理必须不同：
     * 池线程跑完要清干净，避免池化线程串味；内联跑时若照清不误，会连带把父请求线程自己的
     * trace 绑定、span 栈与注入统计一起抹掉，导致随后的终答汇总丢失埋点。
     * 因此只有「当前线程 ≠ 提交线程」时才做清理，{@code submitter} 即为此判据。
     *
     * @param submitter 提交任务的线程；内联执行时等于当前线程
     * @param modelTextOverride 非空时直接用该文本送子 ReAct（固定串行按前置摘要拼好）；
     *                          空则按模板/意图自行解析（判路由与固定并行走这条）
     */
    private ChildOutcome runOneChild(AgentDef parent, RoutedIntent intent, String modelUser, String userText,
                                     long userId, long sessionId, long deadlineNanos, TrialProgress progress,
                                     ObsTraceScope trace, Long childParent, boolean parallelWorker,
                                     boolean isolateChild, Thread submitter, String modelTextOverride) {
        return runOneChild(parent, intent, modelUser, userText, userId, sessionId, deadlineNanos, progress,
                trace, childParent, parallelWorker, isolateChild, submitter, modelTextOverride, null);
    }

    /**
     * 同 {@link #runOneChild(AgentDef, RoutedIntent, String, String, long, long, long, TrialProgress,
     * ObsTraceScope, Long, boolean, boolean, Thread, String)}；额外写入角色类 attrs（评审环专用）。
     *
     * @param extraSpanAttrs 附加到子 AGENT span 的扁平字段；可空（普通模板传 null）
     */
    private ChildOutcome runOneChild(AgentDef parent, RoutedIntent intent, String modelUser, String userText,
                                     long userId, long sessionId, long deadlineNanos, TrialProgress progress,
                                     ObsTraceScope trace, Long childParent, boolean parallelWorker,
                                     boolean isolateChild, Thread submitter, String modelTextOverride,
                                     Map<String, Object> extraSpanAttrs) {
        if (trace != null && parallelWorker) {
            // 并行子线程各自 bind trace，并拿到独立 stack（ThreadLocal.withInitial）
            ObsTraceHolder.bind(trace);
            trace.stack();
        }
        Long agentSpan = null;
        // 共享草稿区：本子用同一个 route_key 作为写入归属（并行时谁先写谁占位）
        ScratchContext.bindOwner(scratchOwner(intent));
        try {
            assertParentDeadline(deadlineNanos);
            Candidate hit = intent.candidate();
            // 多意图：仅 sub_query；单意图：可保留父历史前缀。span 展示一律只用 sub_query
            ChildSubQueryInput.Result childInput = modelTextOverride != null
                    ? new ChildSubQueryInput.Result(modelTextOverride, intent.subQuery(), false)
                    : ChildSubQueryInput.resolve(modelUser, userText, intent.subQuery(), isolateChild);
            // 多意图：子开跑推「路由到」，并以 lane 包装后续增量，并行交错时仍能切回本子块
            String childRouteLine = intent.routeLine() != null ? intent.routeLine()
                    : routeSummary(hit, intent.reason());
            TrialProgress childProgress = progress;
            if (progress != null && parallelWorker) {
                progress.route(childRouteLine);
                childProgress = laneBound(progress, childRouteLine);
            }
            // 每子显式带 childParent（判路由 LLM）；并行发 N.1/N.2；禁写成根/null、禁靠共享 ThreadLocal
            agentSpan = trace == null ? null
                    : trace.open(SpanType.AGENT, hit.agent().getName(), hit.agent().getId(), childParent,
                    childInput.displaySubQuery(), parallelWorker);
            if (trace != null) {
                trace.bindParent(agentSpan);
            }
            // 子 Agent 复用父会话的记忆策略；记忆段在装配时进链（工具目录之前）。
            // P&E 子不在这里装配 ReAct：规划与各步装配由 PlanTrialExecutor 完成（子自身 auto/模型，A7 不继承父）。
            TrialTurn turn;
            List<String> notices;
            if (AgentRunMode.PLAN_AND_EXECUTE.name().equals(hit.agent().getRunMode())) {
                PlanChildResult planned = runPlanChild(parent, hit, intent, childInput, userId, sessionId,
                        deadlineNanos, childProgress);
                turn = planned.turn();
                notices = planned.notices();
            } else {
                TrialMemoryCoordinator.Augmented recalled = memory.recallDurable(
                        memorySession(parent.getId(), userId, sessionId));
                TrialPlan plan = loader.loadReact(hit.agent(), childInput.displaySubQuery(), recalled.memoryBlock());
                plan.setToolArgsFacts(recalled.injectedFacts());
                memory.publishInjection(plan);
                long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                loader.tightenTimeout(plan, Math.min(plan.getLimits().timeoutMs(), remainingMs));
                turn = react.execute(plan, childInput.modelText(), userId, sessionId, childProgress);
                notices = plan.getNotices();
            }
            assertParentDeadline(deadlineNanos);
            if (trace != null) {
                SpanClose agentOk = new SpanClose();
                agentOk.setStatus(ObsStatus.OK);
                agentOk.setInputText(childInput.displaySubQuery());
                agentOk.setOutputText("执行完成");
                agentOk.setAttrsJson(childAgentAttrs(hit.agent(), "执行子 Agent", childInput.fallback(),
                        extraSpanAttrs));
                trace.close(agentSpan, agentOk);
            }
            return ChildOutcome.ok(intent, notices, turn);
        } catch (RuntimeException ex) {
            // 子 Agent 失败：并行时被压成失败段（用户可见文案走清洗），但栈必须留在日志里
            log.warn("子 Agent 执行失败 agentId={} parallel={} type={} cause={}",
                    parent.getId(), parallelWorker, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            if (trace != null && agentSpan != null) {
                SpanClose agentErr = new SpanClose();
                agentErr.setStatus(ObsStatus.ERROR);
                agentErr.setErrorSummary(ex.getMessage());
                agentErr.setOutputText(ex.getMessage());
                agentErr.setAttrsJson(ObsAttrs.withCause(ObsAttrs.withBrief(null, "子 Agent 失败"), CauseDigest.of(ex)));
                trace.close(agentSpan, agentErr);
            }
            // 并行时单子失败不抛出，写成失败段；单子路径保持抛出（与现网一致）
            if (parallelWorker) {
                // 失败段正文会进终答拼接：边界处清洗，避免路径/端口/异常码被复述给用户
                return ChildOutcome.failed(intent, UserFacingText.safeMessage(ex, "子 Agent 执行失败"));
            }
            throw ex;
        } finally {
            if (trace != null) {
                trace.bindParent(null);
                // 仅池线程需清理；CallerRuns 内联执行时清理会误伤父请求线程的 trace 绑定
                if (parallelWorker && Thread.currentThread() != submitter) {
                    trace.clearThreadLocals();
                    ObsTraceHolder.clear();
                    PromptInjectionHolder.clear();
                }
            }
        }
    }

    /**
     * 跑一个 PLAN_AND_EXECUTE 子：规划 →（可选）冒泡待批 → 逐步执行 → 子内汇总。
     *
     * <p>复用 {@link PlanTrialExecutor} 的单 Agent 实现，不另造平行执行器：
     * 模型/工具/技能装配、逐步串行、单步重试、计费埋点全部同一套；
     * 这里只负责「子模式派发」与「批准冒泡」两件事。
     *
     * <p>批准冒泡（子 require_plan_confirm=true）：
     * <ol>
     *   <li>先抢「批准槽」——同一根会话同时仅 1 个待批子，抢不到就等（不开始规划）；</li>
     *   <li>规划完成后登记待批并推 {@code ma_child_plan_pending}，子线程阻塞等决定；</li>
     *   <li>批准 → 续跑并汇总；拒绝/超时/根取消 → 该子失败占位，其它子继续。</li>
     * </ol>
     *
     * @param parent        编排根（用于日志与记忆会话归属）
     * @param hit           命中的候选（路由行 + 子 Agent）
     * @param intent        子意图（sub_query / route_key）
     * @param childInput    已解析的子输入（隔离 vs 继承父历史）
     * @param deadlineNanos 父墙钟截止
     * @param progress      子增量出口（已按 lane 包装）
     * @return 子终答与装配说明
     * @throws TrialFailure 规划失败、批准超时 / 被取消 / 未获批准、或执行失败
     */
    private PlanChildResult runPlanChild(AgentDef parent, Candidate hit, RoutedIntent intent,
                                         ChildSubQueryInput.Result childInput, long userId, long sessionId,
                                         long deadlineNanos, TrialProgress progress) {
        AgentDef child = hit.agent();
        AgentRuntimeConfig childRuntime = AgentRuntimeConfig.parse(child.getRuntimeConfigJson(), child.getRunMode());
        // A7：是否需人工批准由子自身配置决定，不继承父编排根
        if (!childRuntime.requirePlanConfirmOn()) {
            PlanTrialExecutor.PlannedTurn planned = planRunner.plan(child, childInput.displaySubQuery(),
                    childInput.modelText(), userId, sessionId, progress);
            PendingPlan pending = pendingPlan(planned, sessionId, child.getId(), userId, childInput);
            // openRoot=false：复用调用方已挂的子 AGENT，PLAN 与各步成为串行兄弟，不出现假并行
            PlanTrialExecutor.ExecutedTurn executed = planRunner.execute(child, pending, progress, false);
            return new PlanChildResult(executed.turn(), noticesOf(planned));
        }
        String routeKey = routeKeyOf(intent);
        long waitMs = Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        // 单批准槽：先占槽再规划；占不到说明已有别的子待批，本子排队不启动
        if (!childApprovals.claimSlot(sessionId, waitMs)) {
            throw new TrialFailure("等待子计划批准槽超时：同一时刻仅允许 1 个子计划待批准");
        }
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        try {
            PlanTrialExecutor.PlannedTurn planned = planRunner.plan(child, childInput.displaySubQuery(),
                    childInput.modelText(), userId, sessionId, progress);
            PendingPlan pending = pendingPlan(planned, sessionId, child.getId(), userId, childInput);
            ChildPlanPendingView view = new ChildPlanPendingView(routeKey, child.getId(), childLabel(child),
                    planned.planId(), PlanState.PENDING.name(), planned.steps(),
                    ChildPlanPendingView.RUN_STATE_PAUSED);
            childApprovals.publish(new ChildPlanApprovalRegistry.PendingChildPlan(sessionId, routeKey,
                    child.getId(), childLabel(child), pending, decision, LocalDateTime.now()));
            if (progress != null) {
                progress.childPlanPending(view);
            }
            if (!awaitChildDecision(decision, deadlineNanos)) {
                throw new TrialFailure("计划未获批准：该子已跳过");
            }
            PlanTrialExecutor.ExecutedTurn executed = planRunner.execute(child, pending, progress, false);
            log.info("子计划执行完成 parentId={} childId={} routeKey={} sessionId={}",
                    parent.getId(), child.getId(), routeKey, sessionId);
            return new PlanChildResult(executed.turn(), noticesOf(planned));
        } finally {
            // 无论成功/失败/超时都清登记与槽：否则后续需确认的 P&E 子会一直排在队里
            childApprovals.clear(sessionId, routeKey);
            childApprovals.releaseSlot(sessionId);
        }
    }

    /**
     * 等客户端批准 / 拒绝。分段等待是为了让父墙钟与根取消能及时收口。
     *
     * @param decision      批准(true)/拒绝(false) 的完成信号
     * @param deadlineNanos 父墙钟截止
     * @return true＝批准；false＝拒绝或被取消
     * @throws TrialFailure 超时或被中断
     */
    private boolean awaitChildDecision(CompletableFuture<Boolean> decision, long deadlineNanos) {
        while (true) {
            long remainMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainMs <= 0) {
                throw new TrialFailure("等待子计划批准超时（未在墙钟内批准）", LimitHitReasons.TIMEOUT);
            }
            try {
                return Boolean.TRUE.equals(decision.get(Math.min(remainMs, 1000L), TimeUnit.MILLISECONDS));
            } catch (TimeoutException ex) {
                // 继续等：只是一段观察窗口到期，不代表批准失败
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new TrialFailure("等待子计划批准被中断");
            } catch (ExecutionException | CancellationException ex) {
                return false;
            }
        }
    }

    /** 规划结果 → 可续跑的计划登记项（沿用单 Agent 同一份上下文，禁止重拼记忆）。 */
    private static PendingPlan pendingPlan(PlanTrialExecutor.PlannedTurn planned, long sessionId, Long childAgentId,
                                           long userId, ChildSubQueryInput.Result childInput) {
        return new PendingPlan(planned.planId(), sessionId, childAgentId, userId,
                childInput.displaySubQuery(), childInput.modelText(), planned.steps(), LocalDateTime.now());
    }

    private static List<String> noticesOf(PlanTrialExecutor.PlannedTurn planned) {
        List<String> notices = planned.plan() == null ? null : planned.plan().getNotices();
        return notices == null ? List.of() : new ArrayList<>(notices);
    }

    private static String routeKeyOf(RoutedIntent intent) {
        String key = intent.candidate().route().getRouteKey();
        return key == null ? "" : key.trim();
    }

    /**
     * 共享草稿区的写入归属：route_key 优先，评审环等无 key 场景退子 Agent 名。
     *
     * <p>只用于「冲突时告诉模型是谁先写的」与 obs，不参与键名拼接——
     * 用户 key 就是全局的本轮键，不做按 route 的命名空间前缀。
     *
     * @param intent 子意图
     * @return 非空归属标签；极端情况下退空串
     */
    private static String scratchOwner(RoutedIntent intent) {
        String key = routeKeyOf(intent);
        if (!key.isBlank()) {
            return key;
        }
        AgentDef agent = intent.candidate().agent();
        return agent == null || agent.getName() == null ? "" : agent.getName();
    }

    /**
     * 本轮共享草稿的只读统计，合入 ROUTE attrs。
     *
     * <p>只写键数与冲突次数，<b>不写</b>任何 key/value 正文：草稿内容可能含用户数据，
     * 落进追踪表会绕过「草稿不落库」的边界。非 SHARED_SCRATCH 时返回空 Map，
     * {@code ObsAttrs.withFields} 会原样跳过。
     *
     * @return 可合入 attrs 的扁平字段；无板时为空
     */
    private static Map<String, Object> scratchAttrs() {
        ScratchBoard board = ScratchContext.board();
        if (board == null) {
            return Map.of();
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("ma.scratch.key_count", board.keyCount());
        attrs.put("ma.scratch.conflict_count", board.conflictCount());
        return attrs;
    }

    /**
     * P&E 子执行结果。
     *
     * @param turn    子终答与 token
     * @param notices 子装配说明（源不可用 / 技能截断等）
     */
    private record PlanChildResult(TrialTurn turn, List<String> notices) {
    }

    /**
     * CONCAT 按序拼接；SUMMARIZE 用父模型一轮汇总，失败降级 CONCAT 并推思考可见说明。
     * 等齐后再出终答（不做「先完成先推段」）。
     */
    private TrialTurn finishAnswer(AgentDef parent, EffectiveLimits parentLimits, AgentRuntimeConfig runtime,
                                   String userText, List<ChildOutcome> outcomes, List<RoutedIntent> intents,
                                   long userId, long sessionId, long deadlineNanos, TrialProgress progress,
                                   ObsTraceScope trace, Long routeSpan) {
        String concat = concatAnswers(outcomes);
        RouterAnswerMode mode = runtime.answerModeEnum();
        if (mode == RouterAnswerMode.SUMMARIZE && outcomes.size() >= 1) {
            Optional<Long> parentModel = loader.findRouterModel(parent.getId());
            if (parentModel.isPresent()) {
                try {
                    assertParentDeadline(deadlineNanos);
                    if (progress != null) {
                        progress.route("终答汇总中…");
                    }
                    if (trace != null) {
                        // 汇总 LLM 挂在 ROUTE 下，与判路由 LLM 同级兄弟
                        trace.bindParent(routeSpan);
                    }
                    long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                    EffectiveLimits summarizeLimits = parentLimits.withTimeoutMs(
                            Math.min(parentLimits.timeoutMs(), remainingMs));
                    String summary = react.summarize(
                            MeterScope.console(parent.getId(), parentModel.get(), sessionId, userId),
                            summarizePrompt(parent, userText),
                            summarizeUser(userText, outcomes),
                            summarizeLimits);
                    if (summary != null && !summary.isBlank()) {
                        TrialTurn turn = mergeTurns(outcomes, summary.trim());
                        if (progress != null) {
                            progress.assistant(turn.getAssistantText());
                        }
                        return turn;
                    }
                    if (progress != null) {
                        progress.route("汇总无正文，已降级为拼接");
                    }
                } catch (TrialFailure ex) {
                    if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                        throw ex;
                    }
                    if (progress != null) {
                        // 降级说明会进时间线：只给可读短句，异常原文进日志（带栈）
                        log.warn("Router 汇总失败 sessionId={} err={}", sessionId, ex.getMessage(), ex);
                        progress.route("汇总暂时不可用，已改用分段拼接。");
                    }
                } catch (RuntimeException ex) {
                    // 汇总失败降级为拼接：用户面只要一句短话，stderr 里要有完整栈
                    log.warn("Router 汇总异常降级 sessionId={} type={} cause={}",
                            sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    if (progress != null) {
                        progress.route("汇总失败，已降级为拼接");
                    }
                } finally {
                    if (trace != null) {
                        trace.bindParent(null);
                    }
                }
            } else if (progress != null) {
                progress.route("无父模型可汇总，已降级为拼接");
            }
        }
        TrialTurn turn = mergeTurns(outcomes, concat);
        // 多子 / 单子失败 / 曾屏蔽子 assistant：等齐后推终稿；单子 CONCAT 成功时子 ReAct 已流式推过
        boolean needPush = outcomes.size() > 1
                || (outcomes.size() == 1 && outcomes.get(0).error() != null)
                || mode == RouterAnswerMode.SUMMARIZE;
        if (progress != null && needPush) {
            progress.assistant(turn.getAssistantText());
        }
        return turn;
    }

    /**
     * 并行子执行时丢掉 assistant 增量，避免多子正文交错；route/tool/reasoning 仍可见。
     */
    private TrialProgress muteAssistant(TrialProgress progress) {
        if (progress == null) {
            return null;
        }
        return new TrialProgress() {
            @Override
            public void phase(String phase) {
                progress.phase(phase);
            }

            @Override
            public void route(String summary) {
                progress.route(summary);
            }

            @Override
            public void reasoning(String delta) {
                progress.reasoning(delta);
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
                progress.tool(name, arguments, result, success);
            }

            @Override
            public void assistant(String delta) {
                // 多子等齐后再出终答
            }

            /**
             * 产物必须原样透传：静音的是助手正文，不是文件生成。
             * 漏掉这个覆写会让并行 / 汇总路径下的下载卡片凭空消失（默认实现是空操作）。
             */
            @Override
            public void file(GeneratedFile file) {
                progress.file(file);
            }

            /**
             * 子计划待批必须原样透传：静音只针对助手正文，批准气泡不能被吞掉。
             */
            @Override
            public void childPlanPending(ChildPlanPendingView pending) {
                progress.childPlanPending(pending);
            }
        };
    }

    /**
     * 子增量前重推本子「路由到」，供前端在并行交错时切回对应分块；连续相同路由由前端去重。
     */
    private TrialProgress laneBound(TrialProgress progress, String routeLine) {
        if (progress == null) {
            return null;
        }
        String line = routeLine == null ? "" : routeLine;
        return new TrialProgress() {
            @Override
            public void phase(String phase) {
                progress.phase(phase);
            }

            @Override
            public void route(String summary) {
                progress.route(summary);
            }

            @Override
            public void reasoning(String delta) {
                if (!line.isBlank()) {
                    progress.route(line);
                }
                progress.reasoning(delta);
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
                if (!line.isBlank()) {
                    progress.route(line);
                }
                progress.tool(name, arguments, result, success);
            }

            @Override
            public void assistant(String delta) {
                progress.assistant(delta);
            }

            /**
             * 产物不受分块包装影响：文件名与体积是全局事实，
             * 重推「路由到」对下载卡片没有意义，直接透传即可。
             */
            @Override
            public void file(GeneratedFile file) {
                progress.file(file);
            }

            /**
             * 子计划待批要先把「路由到」补上，再透传待批：并行交错时前端仍能切回本子泳道。
             */
            @Override
            public void childPlanPending(ChildPlanPendingView pending) {
                if (!line.isBlank()) {
                    progress.route(line);
                }
                progress.childPlanPending(pending);
            }
        };
    }

    private String concatAnswers(List<ChildOutcome> outcomes) {
        StringBuilder text = new StringBuilder();
        for (ChildOutcome outcome : outcomes) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append("### ").append(sectionTitle(outcome)).append('\n');
            if (outcome.skipped()) {
                // 跳过不是失败：明确写「已跳过」，避免用户以为子 Agent 跑挂了
                text.append("（已跳过）").append(outcome.error());
            } else if (outcome.error() != null) {
                text.append("（执行失败）").append(outcome.error());
            } else if (outcome.turn() != null && outcome.turn().getAssistantText() != null
                    && !outcome.turn().getAssistantText().isBlank()) {
                text.append(outcome.turn().getAssistantText().trim());
            } else {
                text.append("（无实质回答）");
            }
        }
        return text.toString();
    }

    private String sectionTitle(ChildOutcome outcome) {
        Candidate hit = outcome.intent().candidate();
        String intent = hit.route().getIntent();
        if (intent != null && !intent.isBlank()) {
            return intent.trim();
        }
        return childLabel(hit.agent());
    }

    private TrialTurn mergeTurns(List<ChildOutcome> outcomes, String assistantText) {
        TrialTurn merged = new TrialTurn();
        merged.setAssistantText(assistantText == null ? "" : assistantText);
        int prompt = 0;
        int completion = 0;
        int toolMax = 0;
        List<TrialTrace> traces = new ArrayList<>();
        for (ChildOutcome outcome : outcomes) {
            if (outcome.turn() == null) {
                continue;
            }
            if (outcome.turn().getPromptTokens() != null) {
                prompt += outcome.turn().getPromptTokens();
            }
            if (outcome.turn().getCompletionTokens() != null) {
                completion += outcome.turn().getCompletionTokens();
            }
            toolMax = Math.max(toolMax, outcome.turn().getToolResultMaxChars());
            merged.mergeFiles(outcome.turn().getFiles());
            if (outcome.turn().getTraces() != null) {
                traces.addAll(outcome.turn().getTraces());
            }
        }
        merged.setPromptTokens(prompt);
        merged.setCompletionTokens(completion);
        merged.setToolResultMaxChars(toolMax);
        merged.setTraces(traces);
        return merged;
    }

    private String summarizePrompt(AgentDef parent, String userText) {
        StringBuilder prompt = new StringBuilder();
        // ROUTER 用途模板里的 {{user_query}} 需要本轮原问；否则父汇总阶段读不到用户问了什么
        String assembled = prompts.build(parent, userText);
        if (!assembled.isBlank()) {
            prompt.append(assembled).append("\n\n");
        }
        prompt.append("你是路由父助手。下方给出用户原问与各子 Agent 的回答（已打码敏感信息）。\n");
        prompt.append("请综合成一篇连贯、完整的中文答复，不要罗列「子 Agent」字样，不要编造子回答中没有的事实。\n");
        return prompt.toString();
    }
    private String summarizeUser(String userText, List<ChildOutcome> outcomes) {
        StringBuilder text = new StringBuilder();
        text.append("用户原问：\n").append(userText == null ? "" : userText.trim()).append("\n\n");
        text.append("各子回答：\n");
        for (ChildOutcome outcome : outcomes) {
            text.append("- ").append(sectionTitle(outcome)).append("：");
            if (outcome.skipped()) {
                text.append("（已跳过）").append(outcome.error());
            } else if (outcome.error() != null) {
                text.append("（失败）").append(outcome.error());
            } else if (outcome.turn() != null) {
                text.append(outcome.turn().getAssistantText() == null ? "" : outcome.turn().getAssistantText().trim());
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * 子 AGENT attrs：brief + 子运行模式打点 + 空 sub_query 回退整句时的观测标记。
     *
     * @param agent            子 Agent（用于标注计划执行子）
     * @param brief            人话摘要
     * @param subQueryFallback 是否回退用了用户原问
     * @return attrs JSON
     */
    private static String childAgentAttrs(AgentDef agent, String brief, boolean subQueryFallback) {
        return childAgentAttrs(agent, brief, subQueryFallback, null);
    }

    /**
     * 同 {@link #childAgentAttrs(AgentDef, String, boolean)}，并合入调用方附加的扁平字段。
     *
     * <p>评审环用它对子 AGENT span 标注 {@code ma.role=executor|critic} 与
     * {@code ma.critic_round}，让泳道在不改父链的前提下区分「初稿 / 评审 / 回修」。
     *
     * @param extra 附加字段；可空
     * @return attrs JSON
     */
    private static String childAgentAttrs(AgentDef agent, String brief, boolean subQueryFallback,
                                          Map<String, Object> extra) {
        Map<String, Object> fields = new LinkedHashMap<>();
        // 只在 P&E 子上打点：REACT 子是默认形态，不打点也不影响既有面板
        if (agent != null && AgentRunMode.PLAN_AND_EXECUTE.name().equals(agent.getRunMode())) {
            fields.put("ma.child_run_mode", AgentRunMode.PLAN_AND_EXECUTE.name());
        }
        if (subQueryFallback) {
            fields.put("sub_query_fallback", true);
        }
        if (extra != null && !extra.isEmpty()) {
            fields.putAll(extra);
        }
        return ObsAttrs.withFields(ObsAttrs.withBrief(null, brief), fields);
    }

    private List<RoutedIntent> choose(AgentDef parent, EffectiveLimits parentLimits, AgentRuntimeConfig runtime,
                                      String userText, List<Candidate> candidates, long userId, long sessionId,
                                      long deadlineNanos) {
        int maxN = runtime.maxRouteChildrenOrDefault();
        List<AgentRoute> keywordHits = RouteKeywordPicker.matchAll(userText,
                candidates.stream().map(Candidate::route).toList(),
                runtime.multiIntentOn() ? maxN : 1);
        if (!keywordHits.isEmpty()) {
            List<RoutedIntent> intents = new ArrayList<>();
            for (AgentRoute route : keywordHits) {
                Candidate hit = find(candidates, route.getChildAgentId());
                // 关键字无法可靠切句，整句作 sub_query
                intents.add(new RoutedIntent(hit, userText, "关键字"));
            }
            return intents;
        }
        Optional<Long> parentModel = loader.findRouterModel(parent.getId());
        if (parentModel.isEmpty()) {
            Candidate first = candidates.get(0);
            return List.of(new RoutedIntent(first, userText, "未命中关键字且父 Agent 没有对话模型，使用第一条"));
        }
        try {
            assertParentDeadline(deadlineNanos);
            long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            EffectiveLimits judgeLimits = parentLimits.withTimeoutMs(Math.min(parentLimits.timeoutMs(), remainingMs));
            String reply = react.judge(
                    MeterScope.console(parent.getId(), parentModel.get(), sessionId, userId),
                    judgePrompt(parent, candidates, runtime, userText), userText, judgeLimits);
            List<String> keys = candidates.stream()
                    .map(item -> item.route().getRouteKey())
                    .filter(key -> key != null && !key.isBlank())
                    .toList();
            List<RouteDecisionParser.Item> parsed = RouteDecisionParser.parse(reply, keys, maxN, userText);
            if (!runtime.multiIntentOn() && parsed.size() > 1) {
                parsed = List.of(parsed.get(0));
            }
            if (!parsed.isEmpty()) {
                Map<String, Candidate> byKey = new LinkedHashMap<>();
                for (Candidate candidate : candidates) {
                    String key = candidate.route().getRouteKey();
                    if (key != null && !key.isBlank()) {
                        byKey.putIfAbsent(key.trim().toLowerCase(), candidate);
                    }
                }
                List<RoutedIntent> intents = new ArrayList<>();
                for (RouteDecisionParser.Item item : parsed) {
                    Candidate hit = byKey.get(item.routeKey().toLowerCase());
                    if (hit != null) {
                        intents.add(new RoutedIntent(hit, item.subQuery(), "父模型"));
                    }
                }
                if (!intents.isEmpty()) {
                    return intents;
                }
            }
        } catch (TrialFailure ex) {
            if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                throw ex;
            }
            Candidate first = candidates.get(0);
            return List.of(new RoutedIntent(first, userText, "父模型判定失败，使用第一条"));
        }
        Candidate first = candidates.get(0);
        return List.of(new RoutedIntent(first, userText, "父模型未给出有效 route_key，使用第一条"));
    }

    /**
     * 严格版判路由（供 {@code ROUTER_THEN_SEQUENCE} 使用）。所属域：runtime。
     *
     * <p>与 {@link #choose} 的唯一差别是<b>不做兜底</b>：判路由是本模板的执行前提，
     * 父模型缺失 / 调用失败 / 解析不到有效 route_key 都要整次 FAIL。若像并行模板那样
     * 「拿第一条候选顶上」，用户看到的串行链其实与判路由无关，会把「模板没生效」藏起来。
     *
     * <p>解析上限用平台硬顶 {@link AgentRuntimeConfig#MAX_ROUTE_CHILDREN_CAP}：
     * 判路由提示词仍按配置的 maxRouteChildren 要求模型最多返回 N 项，
     * 但解析不提前截断，这样「判定条数 > 配置上限」才能被上层识别并标 SKIPPED_QUOTA。
     *
     * @param parent       编排根（日志与计量）
     * @param parentLimits 父墙钟与轮次上限
     * @param runtime      运行配置（多意图开关 / 上限 / 允许的子模式）
     * @param userText     用户原问全文
     * @param candidates   可用候选（已过滤门闩）
     * @return 按判定数组顺序排列的意图；非空
     * @throws TrialFailure 判路由不可用或结果不可用；墙钟超时原样抛出
     */
    private List<RoutedIntent> chooseOrdered(AgentDef parent, EffectiveLimits parentLimits,
                                             AgentRuntimeConfig runtime, String userText,
                                             List<Candidate> candidates, long userId, long sessionId,
                                             long deadlineNanos) {
        int maxN = runtime.maxRouteChildrenOrDefault();
        // 关键字优先与并行模板同口径：本地命中就不必再花一次判路由调用
        List<AgentRoute> keywordHits = RouteKeywordPicker.matchAll(userText,
                candidates.stream().map(Candidate::route).toList(),
                runtime.multiIntentOn() ? maxN : 1);
        if (!keywordHits.isEmpty()) {
            List<RoutedIntent> intents = new ArrayList<>();
            for (AgentRoute route : keywordHits) {
                intents.add(new RoutedIntent(find(candidates, route.getChildAgentId()), userText, "关键字"));
            }
            return intents;
        }
        Optional<Long> parentModel = loader.findRouterModel(parent.getId());
        if (parentModel.isEmpty()) {
            throw new TrialFailure("路由后串行需要先判路由：父 Agent 未绑定「路由」用途的文本模型，"
                    + "请先绑定再试跑（或改用固定串行模板）");
        }
        String reply;
        try {
            assertParentDeadline(deadlineNanos);
            long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            EffectiveLimits judgeLimits = parentLimits.withTimeoutMs(
                    Math.min(parentLimits.timeoutMs(), remainingMs));
            reply = react.judge(
                    MeterScope.console(parent.getId(), parentModel.get(), sessionId, userId),
                    judgePrompt(parent, candidates, runtime, userText), userText, judgeLimits);
        } catch (TrialFailure ex) {
            // 墙钟属于整次失败，保持 finishReason 供上层识别；其它判路由错误统一加中文前缀
            if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                throw ex;
            }
            throw new TrialFailure("判路由失败：" + ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("路由后串行判路由异常 parentId={} type={} cause={}",
                    parent.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            throw new TrialFailure("判路由失败：路由模型调用异常，请稍后重试");
        }
        // 解析放在调用失败之外：模型答非所问属于结果问题，不该被包成「调用失败」
        List<String> keys = candidates.stream()
                .map(item -> item.route().getRouteKey())
                .filter(key -> key != null && !key.isBlank())
                .toList();
        List<RouteDecisionParser.Item> parsed = RouteDecisionParser.parse(reply, keys,
                AgentRuntimeConfig.MAX_ROUTE_CHILDREN_CAP, userText);
        if (!runtime.multiIntentOn() && parsed.size() > 1) {
            parsed = List.of(parsed.get(0));
        }
        if (parsed.isEmpty()) {
            throw new TrialFailure("判路由失败：模型没有给出可用的 route_key，请检查路由模型或候选意图描述");
        }
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String key = candidate.route().getRouteKey();
            if (key != null && !key.isBlank()) {
                byKey.putIfAbsent(key.trim().toLowerCase(), candidate);
            }
        }
        List<RoutedIntent> intents = new ArrayList<>();
        for (RouteDecisionParser.Item item : parsed) {
            Candidate hit = byKey.get(item.routeKey().toLowerCase());
            if (hit != null) {
                intents.add(new RoutedIntent(hit, item.subQuery(), "父模型"));
            }
        }
        if (intents.isEmpty()) {
            throw new TrialFailure("判路由失败：模型给出的 route_key 均未匹配当前候选，请检查候选配置");
        }
        return intents;
    }

    private void assertParentDeadline(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new TrialFailure("撞限：timeout（Router 整次墙钟超时）", LimitHitReasons.TIMEOUT);
        }
    }

    /**
     * 路由展示摘要。无 route_key 时不拼空括号；子 Agent 优先名称，否则编码。
     */
    private String routeSummary(Candidate hit, String reason) {
        String label = childLabel(hit.agent());
        String key = hit.route().getRouteKey() == null ? "" : hit.route().getRouteKey().trim();
        if (key.isEmpty()) {
            return "路由到 " + label + "，依据：" + reason;
        }
        return "路由到 " + label + "（" + key + "），依据：" + reason;
    }

    /** 子 Agent 显示名：名称 → 编码 → 兜底文案。 */
    private String childLabel(AgentDef agent) {
        if (agent.getName() != null && !agent.getName().isBlank()) {
            return agent.getName().trim();
        }
        if (agent.getCode() != null && !agent.getCode().isBlank()) {
            return agent.getCode().trim();
        }
        return "子 Agent";
    }

    private String judgePrompt(AgentDef parent, List<Candidate> candidates, AgentRuntimeConfig runtime,
                               String userText) {
        StringBuilder prompt = new StringBuilder();
        // ROUTER 用途模板可能自带 {{user_query}}；传进去让模板侧占位符有值，避免严格模式失败
        String assembled = prompts.build(parent, userText);
        if (!assembled.isBlank()) {
            prompt.append(assembled).append("\n\n");
        }
        int maxN = runtime.maxRouteChildrenOrDefault();
        if (runtime.multiIntentOn()) {
            prompt.append("本次做意图路由：用户可能含多个意图。输出 JSON 数组，每项 ")
                    .append("{\"route_key\":\"...\",\"sub_query\":\"切给该子的子问题\"}。")
                    .append("按重要性排序；同 route_key 只出现一次；最多 ").append(maxN).append(" 项。")
                    .append("单意图则数组长度为 1。不要解释。都不合适时回复 []。\n");
        } else {
            prompt.append("本次只做路由选择：输出 JSON 数组且仅一项 ")
                    .append("{\"route_key\":\"...\",\"sub_query\":\"...\"}，或只回复一个 route_key。")
                    .append("不要解释。都不合适时回复 [] 或 NONE。\n");
        }
        prompt.append("候选：\n");
        for (Candidate candidate : candidates) {
            prompt.append("- ").append(candidate.route().getRouteKey());
            if (candidate.route().getIntent() != null && !candidate.route().getIntent().isBlank()) {
                prompt.append('：').append(candidate.route().getIntent().trim());
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    /**
     * 记忆策略挂在用户选中的父 Agent 上，不改用子 Agent 的 id。
     */
    private RunSession memorySession(long agentId, long userId, long sessionId) {
        RunSession session = new RunSession();
        session.setId(sessionId);
        session.setAgentId(agentId);
        session.setUserId(userId);
        return session;
    }

    /**
     * 装载候选，并把「未获本编排允许」的子单独挑出来。
     * <p>四道过滤：路由行启用 → 子未删且启用 → 子 run_mode ∈ {REACT, PLAN_AND_EXECUTE} →
     * 子 allow_orchestrated=1 → 子 run_mode ∈ 父 {@code maChildRunModesAllowed}。
     * 后两道单独收集而不是静默丢弃：上层要在路由时间线写「已跳过」原因，
     * 并在过滤后零可用时给出明确中文失败，而不是让用户对着「没有可用子」猜原因。
     *
     * <p>子 ROUTER 仍静默丢弃（结构上就不该存在；写入侧已被 AgentRoutePolicy 挡住）。
     *
     * @param parentId 编排根主键
     * @param runtime  编排根运行配置（读 maChildRunModesAllowed）
     * @return usable=可执行候选；blocked=被开关/子模式挡下的候选
     */
    private Candidates loadCandidates(Long parentId, AgentRuntimeConfig runtime) {
        List<Candidate> usable = new ArrayList<>();
        List<BlockedCandidate> blocked = new ArrayList<>();
        for (AgentRoute route : routes.listByParentId(parentId)) {
            if (route.getStatus() == null || route.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            Optional<AgentDef> found = agents.findById(route.getChildAgentId())
                    .filter(item -> item.getStatus() != null && item.getStatus() == AgentStatus.ENABLED)
                    .filter(item -> AgentRuntimeConfig.validChildRunMode(item.getRunMode()));
            if (found.isEmpty()) {
                continue;
            }
            Candidate candidate = new Candidate(route, found.get());
            String runMode = found.get().getRunMode();
            // 子运行模式必须先被本编排允许，再看被编排开关：两者都关时才给一条最贴近原因的跳过文案
            if (!runtime.allowsChildRunMode(runMode)) {
                blocked.add(new BlockedCandidate(candidate, runMode == null ? "" : runMode,
                        "本编排未允许子使用" + childModeText(runMode) + "模式"));
            } else if (!found.get().allowOrchestratedOn()) {
                blocked.add(new BlockedCandidate(candidate, runMode, "专家未允许被编排调用"));
            } else {
                usable.add(candidate);
            }
        }
        return new Candidates(usable, blocked);
    }

    /**
     * 汇总被挡下候选的具体原因（去重、保持出现顺序），供零可用时的中文失败文案。
     *
     * <p>把原因摊开而不是写「被编排开关或子运行模式」这种二选一模糊话术：
     * 用户需要知道到底是去目标 Agent 开 allow_orchestrated，还是回编排 Tab 勾选「计划执行」。
     *
     * @param blocked 被挡下的候选，非空
     * @return 以「、」连接的中文原因
     */
    private static String blockedReasons(List<BlockedCandidate> blocked) {
        java.util.LinkedHashSet<String> reasons = new java.util.LinkedHashSet<>();
        for (BlockedCandidate item : blocked) {
            reasons.add(item.reason());
        }
        return String.join("、", reasons);
    }

    /**
     * 把被挡下的子转成「已跳过」占位。
     * <p>不伪造执行：turn 为空、skipped=true，因此不进终答拼接、不计 token，只在路由时间线留中文原因。
     *
     * @param blocked  被挡下的候选
     * @param userText 用户原问（仅作占位意图的 subQuery，不会真送给子）
     * @return 跳过占位行，顺序与 blocked 一致
     */
    private List<ChildOutcome> blockedOutcomes(List<BlockedCandidate> blocked, String userText) {
        List<ChildOutcome> outcomes = new ArrayList<>(blocked.size());
        for (BlockedCandidate blockedItem : blocked) {
            Candidate candidate = blockedItem.candidate();
            String label = childLabel(candidate.agent());
            String reason = blockedItem.reason();
            outcomes.add(ChildOutcome.skipped(
                    new RoutedIntent(candidate, userText, reason,
                            "已跳过 " + label + "：" + reason + "（" + childModeText(blockedItem.runMode()) + "）"),
                    "「" + label + "」" + reason + "，已跳过"));
        }
        return outcomes;
    }

    /**
     * @param runMode REACT / PLAN_AND_EXECUTE
     * @return 中文模式名
     */
    private static String childModeText(String runMode) {
        return AgentRunMode.PLAN_AND_EXECUTE.name().equals(runMode) ? "计划执行" : "ReAct";
    }

    /**
     * 候选装载结果。
     *
     * @param usable  可执行候选
     * @param blocked 被编排开关 / 子运行模式挡下的候选（用于跳过占位与失败话术）
     */
    private record Candidates(List<Candidate> usable, List<BlockedCandidate> blocked) {
    }

    /**
     * 被挡下的候选与原因。
     *
     * @param candidate 路由行 + 子 Agent
     * @param runMode   子的运行模式（ROUTER 之外只有 REACT / PLAN_AND_EXECUTE）
     * @param reason    中文原因，进时间线
     */
    private record BlockedCandidate(Candidate candidate, String runMode, String reason) {
    }

    private Candidate find(List<Candidate> candidates, Long childId) {
        return candidates.stream()
                .filter(item -> item.agent().getId().equals(childId))
                .findFirst()
                .orElse(candidates.get(0));
    }

    /**
     * @param summary       兼容旧展示的合并摘要，不含密钥
     * @param decisionBlock 多意图时的「路由决策」正文（含 JSON）；单意图为 null
     * @param segments      按子分段：每段一条「路由到」+ 该子 traces，供落库保持分块
     * @param notices       子 Agent 装配说明
     * @param turn          合并后的执行结果（终答 / token）；traces 可能已拍扁，落库优先用 segments
     */
    public record RoutedTurn(String summary, String decisionBlock, List<RouteSegment> segments,
                             List<String> notices, TrialTurn turn) {
    }

    /**
     * 思考落库用的一个子 Agent 段。
     *
     * @param routeLine 「路由到 {名}（key），依据：…」
     * @param turn      该子执行结果；失败时可空
     * @param error     失败摘要；成功为 null
     */
    public record RouteSegment(String routeLine, TrialTurn turn, String error) {
    }

    private record Candidate(AgentRoute route, AgentDef agent) {
    }

    /**
     * 一次子执行意图。
     *
     * @param candidate 命中的候选（路由行 + 子 Agent）
     * @param subQuery  给该子的子问题（固定模板即用户原问全文）
     * @param reason    判定依据（固定模板写「固定并行 / 固定串行」）
     * @param routeLine 思考/泳道用的本子标题；null 时按判路由语义生成「路由到…」
     */
    private record RoutedIntent(Candidate candidate, String subQuery, String reason, String routeLine) {
        RoutedIntent(Candidate candidate, String subQuery, String reason) {
            this(candidate, subQuery, reason, null);
        }
    }

    /**
     * 一个子 Agent 的执行结果。
     *
     * @param skipped true＝因超出并行上限未执行（error 里放中文跳过说明）；false＝真跑过
     */
    private record ChildOutcome(RoutedIntent intent, List<String> notices, TrialTurn turn, String error,
                                boolean skipped) {
        static ChildOutcome ok(RoutedIntent intent, List<String> notices, TrialTurn turn) {
            return new ChildOutcome(intent, notices == null ? List.of() : new ArrayList<>(notices), turn, null, false);
        }

        static ChildOutcome failed(RoutedIntent intent, String error) {
            return new ChildOutcome(intent, List.of(), null, error, false);
        }

        static ChildOutcome skipped(RoutedIntent intent, String note) {
            return new ChildOutcome(intent, List.of(), null, note, true);
        }
    }
}

package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.RouterAnswerMode;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.ChildSubQueryInput;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import com.agenthub.runtime.domain.service.RouteDecisionParser;
import com.agenthub.runtime.domain.service.RouteKeywordPicker;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一层 Router。所属域：runtime。
 * 判路由输出有序 [{route_key, sub_query}, ...]；≥2 子并行 ReAct；终答默认 CONCAT，可配 SUMMARIZE。
 * 多意图子执行强制隔离上下文（仅 sub_query，见 {@link ChildSubQueryInput}）；单意图可继承父历史。
 * 思考过程按子分段推送/落库，禁止收束拍扁成单条路由摘要。一子失败其它继续。
 * 观测挂点：根 ROUTE → 判路由 LLM →（子 AGENT∥…）→ 子内；子 AGENT 禁止挂 ROUTE/根。
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

    /**
     * @param routes        子路由
     * @param agents        子 Agent 定义
     * @param loader        ReAct 装配
     * @param react         官方 ReAct 执行
     * @param prompts       父 Agent 判定/汇总也要带平台前缀
     * @param limitResolver 父墙钟与子轮次
     * @param memory        子 Agent 补记忆段并公布注入统计
     */
    public RouterTrialExecutor(AgentRouteRepository routes, AgentDefRepository agents,
                               TrialBindingLoader loader, ReActTrialExecutor react, SystemPromptAssembler prompts,
                               RuntimeLimitResolver limitResolver, TrialMemoryCoordinator memory) {
        this.routes = routes;
        this.agents = agents;
        this.loader = loader;
        this.react = react;
        this.prompts = prompts;
        this.limitResolver = limitResolver;
        this.memory = memory;
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
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        Long routeSpan = trace == null ? null : trace.open(SpanType.ROUTE, "judge", parent.getId(), null, null);
        if (trace != null) {
            trace.bindParent(routeSpan);
        }
        AtomicBoolean routeClosed = new AtomicBoolean(false);
        try {
            assertParentDeadline(deadlineNanos);
            List<Candidate> candidates = candidates(parent.getId());
            if (candidates.isEmpty()) {
                throw new TrialFailure("Router 没有可用的子 Agent");
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
                routeClose.setAttrsJson(ObsAttrs.withBrief(null,
                        intents.size() <= 1 ? "路由到 " + childLabel(intents.get(0).candidate().agent())
                                : "多意图路由×" + intents.size()));
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
            TrialTurn turn = finishAnswer(parent, parentLimits, runtime, userText, outcomes, intents,
                    userId, sessionId, deadlineNanos, progress, trace, routeSpan);
            String decisionBlock = intents.size() >= 2 ? "路由决策\n" + decisionJson : null;
            return new RoutedTurn(String.join("；", routeLines), decisionBlock, segments, notices, turn);
        } catch (RuntimeException ex) {
            if (trace != null && !routeClosed.get()) {
                SpanClose routeErr = new SpanClose();
                routeErr.setStatus(ObsStatus.ERROR);
                routeErr.setErrorSummary(ex.getMessage());
                routeErr.setOutputText(ex.getMessage());
                routeErr.setAttrsJson(ObsAttrs.withBrief(null, "路由失败"));
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
     * ≥2 并行；=1 与现网一致。失败子写入 outcome.error，不打断其它子。
     *
     * @param childParent  子 AGENT 的 parent_id（判路由 LLM，或无 LLM 时的 ROUTE）
     * @param isolateChild 多意图时 true：子仅吃 sub_query
     */
    private List<ChildOutcome> runChildren(AgentDef parent, List<RoutedIntent> intents, String modelUser,
                                           String userText, long userId, long sessionId, long deadlineNanos,
                                           TrialProgress progress, ObsTraceScope trace, Long childParent,
                                           boolean isolateChild) {
        if (intents.size() == 1) {
            return List.of(runOneChild(parent, intents.get(0), modelUser, userText, userId, sessionId,
                    deadlineNanos, progress, trace, childParent, false, isolateChild));
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(intents.size(),
                AgentRuntimeConfig.MAX_ROUTE_CHILDREN_CAP));
        try {
            List<CompletableFuture<ChildOutcome>> futures = new ArrayList<>();
            for (RoutedIntent intent : intents) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> runOneChild(parent, intent, modelUser, userText, userId, sessionId,
                                deadlineNanos, progress, trace, childParent, true, isolateChild),
                        pool));
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
                } catch (Exception ex) {
                    cancelPending(futures);
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    // 失败段正文会进终答拼接（用户可见）：统一走边界清洗，不给路径/端口/异常码
                    outcomes.add(ChildOutcome.failed(intents.get(i),
                            UserFacingText.safeMessage(cause, "子 Agent 执行失败")));
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void cancelPending(List<CompletableFuture<ChildOutcome>> futures) {
        for (CompletableFuture<ChildOutcome> future : futures) {
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

    private ChildOutcome runOneChild(AgentDef parent, RoutedIntent intent, String modelUser, String userText,
                                     long userId, long sessionId, long deadlineNanos, TrialProgress progress,
                                     ObsTraceScope trace, Long childParent, boolean parallelWorker,
                                     boolean isolateChild) {
        if (trace != null && parallelWorker) {
            // 并行子线程各自 bind trace，并拿到独立 stack（ThreadLocal.withInitial）
            ObsTraceHolder.bind(trace);
            trace.stack();
        }
        Long agentSpan = null;
        try {
            assertParentDeadline(deadlineNanos);
            Candidate hit = intent.candidate();
            // 多意图：仅 sub_query；单意图：可保留父历史前缀。span 展示一律只用 sub_query
            ChildSubQueryInput.Result childInput = ChildSubQueryInput.resolve(
                    modelUser, userText, intent.subQuery(), isolateChild);
            // 多意图：子开跑推「路由到」，并以 lane 包装后续增量，并行交错时仍能切回本子块
            String childRouteLine = routeSummary(hit, intent.reason());
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
            TrialPlan plan = loader.loadReact(hit.agent(), childInput.displaySubQuery());
            // 子 Agent 复用父会话的记忆策略；工具补参与提示词用同一份已注入记忆
            TrialMemoryCoordinator.Augmented augmented = memory.augmentSystemWithFacts(
                    memorySession(parent.getId(), userId, sessionId), plan.getSystemPrompt());
            plan.setSystemPrompt(augmented.systemPrompt());
            plan.setToolArgsFacts(augmented.injectedFacts());
            memory.publishInjection(plan);
            long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            loader.tightenTimeout(plan, Math.min(plan.getLimits().timeoutMs(), remainingMs));
            TrialTurn turn = react.execute(plan, childInput.modelText(), userId, sessionId, childProgress);
            assertParentDeadline(deadlineNanos);
            if (trace != null) {
                SpanClose agentOk = new SpanClose();
                agentOk.setStatus(ObsStatus.OK);
                agentOk.setInputText(childInput.displaySubQuery());
                agentOk.setOutputText("执行完成");
                agentOk.setAttrsJson(childAgentAttrs("执行子 Agent", childInput.fallback()));
                trace.close(agentSpan, agentOk);
            }
            return ChildOutcome.ok(intent, plan.getNotices(), turn);
        } catch (RuntimeException ex) {
            if (trace != null && agentSpan != null) {
                SpanClose agentErr = new SpanClose();
                agentErr.setStatus(ObsStatus.ERROR);
                agentErr.setErrorSummary(ex.getMessage());
                agentErr.setOutputText(ex.getMessage());
                agentErr.setAttrsJson(ObsAttrs.withBrief(null, "子 Agent 失败"));
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
                if (parallelWorker) {
                    trace.clearThreadLocals();
                    ObsTraceHolder.clear();
                    PromptInjectionHolder.clear();
                }
            }
        }
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
                        // 降级说明会进时间线：只给可读短句，异常原文进日志
                        log.warn("Router 汇总失败 sessionId={} err={}", sessionId, ex.getMessage(), ex);
                        progress.route("汇总暂时不可用，已改用分段拼接。");
                    }
                } catch (RuntimeException ex) {
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
        };
    }

    private String concatAnswers(List<ChildOutcome> outcomes) {
        StringBuilder text = new StringBuilder();
        for (ChildOutcome outcome : outcomes) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append("### ").append(sectionTitle(outcome)).append('\n');
            if (outcome.error() != null) {
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
            if (outcome.error() != null) {
                text.append("（失败）").append(outcome.error());
            } else if (outcome.turn() != null) {
                text.append(outcome.turn().getAssistantText() == null ? "" : outcome.turn().getAssistantText().trim());
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * 子 AGENT attrs：brief + 空 sub_query 回退整句时的观测标记。
     */
    private static String childAgentAttrs(String brief, boolean subQueryFallback) {
        String base = ObsAttrs.withBrief(null, brief);
        if (!subQueryFallback) {
            return base;
        }
        if (base == null || base.isBlank()) {
            return "{\"sub_query_fallback\":true}";
        }
        String trimmed = base.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && !trimmed.contains("\"sub_query_fallback\"")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) {
                return "{\"sub_query_fallback\":true}";
            }
            return "{" + inner + ",\"sub_query_fallback\":true}";
        }
        return "{\"sub_query_fallback\":true,\"brief\":\"" + (brief == null ? "" : brief.replace("\"", "\\\"")) + "\"}";
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

    private List<Candidate> candidates(Long parentId) {
        List<Candidate> list = new ArrayList<>();
        for (AgentRoute route : routes.listByParentId(parentId)) {
            if (route.getStatus() == null || route.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            agents.findById(route.getChildAgentId())
                    .filter(item -> item.getStatus() != null && item.getStatus() == AgentStatus.ENABLED)
                    .filter(item -> AgentRunMode.REACT.name().equals(item.getRunMode()))
                    .ifPresent(agent -> list.add(new Candidate(route, agent)));
        }
        return list;
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

    private record RoutedIntent(Candidate candidate, String subQuery, String reason) {
    }

    private record ChildOutcome(RoutedIntent intent, List<String> notices, TrialTurn turn, String error) {
        static ChildOutcome ok(RoutedIntent intent, List<String> notices, TrialTurn turn) {
            return new ChildOutcome(intent, notices == null ? List.of() : new ArrayList<>(notices), turn, null);
        }

        static ChildOutcome failed(RoutedIntent intent, String error) {
            return new ChildOutcome(intent, List.of(), null, error);
        }
    }
}

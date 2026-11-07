package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.application.ChildPlanApprovalRegistry;
import com.agenthub.runtime.application.PlanTrialExecutor;
import com.agenthub.runtime.application.TrialBindingLoader;
import com.agenthub.runtime.application.TrialMemoryCoordinator;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.ScratchBoard;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.ChildSubQueryInput;
import com.agenthub.runtime.domain.service.FixedSequenceContext;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import com.agenthub.runtime.domain.service.ScratchContext;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子 Agent 执行：并行扇出 / 串行推进 / P&E 子计划与批准冒泡。所属域：runtime。
 *
 * <p>为什么独立成类：这套逻辑是「跑子」的唯一实现，六套编排模板都只是决定「跑谁、按什么顺序」。
 * 从模板执行器里拆出来后，模板分支与子执行互不干扰，线程与取消规则集中一处。
 *
 * <p><b>不可动的契约</b>（动则易出线上问题）：
 * <ul>
 *   <li>并行线程来自注入的 {@code routerTaskExecutor}（有界队列 + CallerRuns），本类<b>不</b>新建或关闭线程池；</li>
 *   <li>池满 CallerRuns 时子任务内联跑在请求线程：只有「当前线程 ≠ 提交线程」才清理 ThreadLocal，
 *       否则会把父请求的 trace 绑定一并抹掉；</li>
 *   <li>共享草稿板必须由提交线程用 {@code ScratchContext.callAs} 带进 worker，ThreadLocal 不跨线程继承；</li>
 *   <li>取消顺序：先 cancel 未完成 Future，再按失败段收束；池本身归容器所有。</li>
 * </ul>
 *
 * <p>构造方式：由 {@code RouterTrialExecutor} 在自身构造器内 new（非 Spring Bean），
 * 保持模板执行器的构造器签名不变。
 */
public class ChildRunner {

    private static final Logger log = LoggerFactory.getLogger(ChildRunner.class);

    /** 子 Agent 的装配与记忆注入 */
    private final TrialBindingLoader loader;

    /** REACT 子执行 */
    private final ReActTrialExecutor react;

    /** 子 Agent 复用父会话的记忆策略 */
    private final TrialMemoryCoordinator memory;

    /** P&E 子的规划 / 续跑 */
    private final PlanTrialExecutor planRunner;

    /** 子计划批准槽与待批登记 */
    private final ChildPlanApprovalRegistry childApprovals;

    /** 并行子执行池；容器所有，禁止 shutdown */
    private final ThreadPoolTaskExecutor routerTaskExecutor;

    /**
     * @param loader             装配 REACT 子计划、收紧超时
     * @param react              REACT 子执行
     * @param memory             记忆召回与注入统计
     * @param planRunner         P&E 子规划与执行
     * @param childApprovals     单批准槽 + 待批登记
     * @param routerTaskExecutor 并行子执行池
     */
    public ChildRunner(TrialBindingLoader loader, ReActTrialExecutor react, TrialMemoryCoordinator memory,
                       PlanTrialExecutor planRunner, ChildPlanApprovalRegistry childApprovals,
                       ThreadPoolTaskExecutor routerTaskExecutor) {
        this.loader = loader;
        this.react = react;
        this.memory = memory;
        this.planRunner = planRunner;
        this.childApprovals = childApprovals;
        this.routerTaskExecutor = routerTaskExecutor;
    }

    /**
     * ≥2 并行；=1 与现网一致。失败子写入 outcome.error，不打断其它子。
     *
     * <p>线程来源：全局 {@code routerTaskExecutor}（有界队列 + CallerRuns），本方法<b>不</b>再新建或关闭线程池；
     * 池满时 CallerRuns 会把子任务退回当前请求线程执行，因此本方法在极端压力下仍可能串行完成。
     *
     * @param parent       编排根
     * @param intents      已排好序的参与者
     * @param modelUser    已注入记忆的用户侧文本
     * @param userText     用户原文
     * @param deadlineNanos 父墙钟截止
     * @param progress     可空
     * @param trace        可空
     * @param childParent  子 AGENT 的 parent_id（判路由 LLM，或无 LLM 时的 ROUTE）
     * @param isolateChild 多意图时 true：子仅吃 sub_query
     * @return 与 intents 同序的子结果
     */
    public List<ChildOutcome> runChildren(AgentDef parent, List<RoutedIntent> intents, String modelUser,
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
                    RouterClock.assertBefore(deadlineNanos);
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

    /**
     * 串行：按 intents 顺序依次执行，前一步的终答摘要进下一步输入；单步失败默认继续后续步。
     * <p>固定串行与路由后串行共用本方法：两者的差别只是 intents 的来源
     * （候选 sort_order vs 判路由数组序）与 sub_query（整段原问 vs 判路由切分）。
     *
     * @param intents 已按执行顺序排好的参与者
     * @param runtime 运行配置（决定是否拼前置摘要、单子是否继承父历史）
     * @return 每步结果，顺序与 intents 一致；失败步 turn 为空
     */
    public List<ChildOutcome> runFixedSequence(AgentDef parent, String modelUser, String userText, long userId,
                                               long sessionId, long deadlineNanos, TrialProgress progress,
                                               ObsTraceScope trace, Long childParent, List<RoutedIntent> intents,
                                               AgentRuntimeConfig runtime) {
        List<ChildOutcome> outcomes = new ArrayList<>(intents.size());
        List<FixedSequenceContext.PriorStep> prior = new ArrayList<>();
        boolean priorSummary = runtime.priorSummaryOn();
        // 单子串行没有「前置」，与现网单意图对齐：允许继承父历史，否则硬隔离
        boolean single = intents.size() == 1;
        for (RoutedIntent intent : intents) {
            RouterClock.assertBefore(deadlineNanos);
            // 每步先推本步分块标题；串行下后续 reasoning/tool 自然落进该块，不需要 lane 重推
            if (progress != null && intent.routeLine() != null) {
                progress.route(intent.routeLine());
            }
            // 多步串行默认只给「本步子问 + 前置终答摘要」，绝不灌父历史/工具原文；单步交回既有解析
            String stepModelText = null;
            boolean isolate;
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
    public ChildOutcome runOneChild(AgentDef parent, RoutedIntent intent, String modelUser, String userText,
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
    public ChildOutcome runOneChild(AgentDef parent, RoutedIntent intent, String modelUser, String userText,
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
            RouterClock.assertBefore(deadlineNanos);
            Candidate hit = intent.candidate();
            // 多意图：仅 sub_query；单意图：可保留父历史前缀。span 展示一律只用 sub_query
            ChildSubQueryInput.Result childInput = modelTextOverride != null
                    ? new ChildSubQueryInput.Result(modelTextOverride, intent.subQuery(), false)
                    : ChildSubQueryInput.resolve(modelUser, userText, intent.subQuery(), isolateChild);
            // 多意图：子开跑推「路由到」，并以 lane 包装后续增量，并行交错时仍能切回本子块
            String childRouteLine = intent.routeLine() != null ? intent.routeLine()
                    : RouterTexts.routeSummary(hit, intent.reason());
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
                // 记忆段在装配时进链（工具目录之前）；召回用本子查询文本，失败自动回落 MySQL
                TrialMemoryCoordinator.Augmented recalled = memory.recallDurable(
                        memorySession(parent.getId(), userId, sessionId), childInput.displaySubQuery());
                TrialPlan plan = loader.loadReact(hit.agent(), childInput.displaySubQuery(), recalled.memoryBlock());
                plan.setToolArgsFacts(recalled.injectedFacts());
                memory.publishInjection(plan);
                long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                loader.tightenTimeout(plan, Math.min(plan.getLimits().timeoutMs(), remainingMs));
                turn = react.execute(plan, childInput.modelText(), userId, sessionId, childProgress);
                notices = plan.getNotices();
            }
            RouterClock.assertBefore(deadlineNanos);
            if (trace != null) {
                SpanClose agentOk = new SpanClose();
                agentOk.setStatus(ObsStatus.OK);
                agentOk.setInputText(childInput.displaySubQuery());
                agentOk.setOutputText("执行完成");
                agentOk.setAttrsJson(RouterObs.childAgentAttrs(hit.agent(), "执行子 Agent", childInput.fallback(),
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
            ChildPlanPendingView view = new ChildPlanPendingView(routeKey, child.getId(), RouterTexts.childLabel(child),
                    planned.planId(), PlanState.PENDING.name(), planned.steps(),
                    ChildPlanPendingView.RUN_STATE_PAUSED);
            childApprovals.publish(new ChildPlanApprovalRegistry.PendingChildPlan(sessionId, routeKey,
                    child.getId(), RouterTexts.childLabel(child), pending, decision, LocalDateTime.now()));
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

    /**
     * 子 AGENT 挂点：优先本 trace 最近判路由 LLM；无则退 ROUTE。禁止 null/根。
     *
     * @param trace     当前埋点；可空
     * @param routeSpan ROUTE 行 id；可空
     * @return 非根挂点；trace/route 皆空时为 null
     */
    public static Long childAgentParent(ObsTraceScope trace, Long routeSpan) {
        if (trace != null) {
            Long judgeLlm = trace.lastLlmSpanId();
            if (judgeLlm != null) {
                return judgeLlm;
            }
        }
        return routeSpan;
    }

    /**
     * 只取消本方法提交的任务；已完成的不动，池本身归容器所有。
     *
     * @param futures 本次提交的任务
     */
    private static void cancelPending(List<Future<ChildOutcome>> futures) {
        for (Future<ChildOutcome> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    /** 规划结果 → 可续跑的计划登记项（沿用单 Agent 同一份上下文，禁止重拼记忆）。 */
    private static PendingPlan pendingPlan(PlanTrialExecutor.PlannedTurn planned, long sessionId, Long childAgentId,
                                           long userId, ChildSubQueryInput.Result childInput) {
        return new PendingPlan(planned.planId(), sessionId, childAgentId, userId,
                childInput.displaySubQuery(), childInput.modelText(), planned.steps(), LocalDateTime.now());
    }

    /**
     * @param planned 规划结果
     * @return 装配说明（计划为空或没有说明时为空列表）
     */
    private static List<String> noticesOf(PlanTrialExecutor.PlannedTurn planned) {
        List<String> notices = planned.plan() == null ? null : planned.plan().getNotices();
        return notices == null ? List.of() : new ArrayList<>(notices);
    }

    /**
     * @param intent 子意图
     * @return route_key；缺失为空串
     */
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
     * 子 Agent 的记忆会话归属：与父同一个 sessionId，记忆策略由父会话决定。
     *
     * @param agentId   编排根 id
     * @param userId    平台用户
     * @param sessionId 试跑会话
     * @return 记忆会话
     */
    private static RunSession memorySession(Long agentId, long userId, long sessionId) {
        RunSession session = new RunSession();
        session.setId(sessionId);
        session.setAgentId(agentId);
        session.setUserId(userId);
        return session;
    }

    /**
     * 子增量前重推本子「路由到」，供前端在并行交错时切回对应分块；连续相同路由由前端去重。
     *
     * @param progress  下游推送
     * @param routeLine 本子标题
     * @return 包装后的推送
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

    /**
     * P&E 子执行结果。
     *
     * @param turn    子终答与 token
     * @param notices 子装配说明（源不可用 / 技能截断等）
     */
    private record PlanChildResult(TrialTurn turn, List<String> notices) {
    }
}

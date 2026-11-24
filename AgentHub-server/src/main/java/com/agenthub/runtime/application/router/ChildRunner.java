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
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.ChoiceCapture;
import com.agenthub.runtime.domain.model.MaPauseMember;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.ScratchBoard;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.ChildSubQueryInput;
import com.agenthub.runtime.domain.service.ChoiceCaptureHolder;
import com.agenthub.runtime.domain.service.EntryMaterialHolder;
import com.agenthub.runtime.domain.service.FixedSequenceContext;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import com.agenthub.runtime.domain.service.ScratchContext;
import com.agenthub.runtime.domain.service.SoleChainHolder;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
     * 嵌套方案（目标方案）内并行子执行的最大并发。
     *
     * <p>为什么单独一个常量：这一层不能回到 {@link #routerTaskExecutor}（会与父线程抢池、
     * 形成自等待），但也不能串行（那会把「路由并行」悄悄改成串行）。用本层专属的小池兜住
     * 一层方案的常见子数即可；超出的按 {@code min(子数, 本值)} 分波推进，仍是并发而非串行。
     */
    private static final int INLINE_PARALLELISM = 4;

    /** 目标方案执行委托（刀5）；普通子 Agent 不走这里 */
    private final MaTargetRunner maTargets;

    /** 目标工作流执行委托（刀5B）；普通子 Agent / 方案目标都不走这里 */
    private final WorkflowTargetRunner workflowTargets;

    /**
     * 兼容构造：未装配工作流委托（大量窄测只验证 Agent / 方案链路）。
     *
     * @param loader             装配 REACT 子计划、收紧超时
     * @param react              REACT 子执行
     * @param memory             记忆召回与注入统计
     * @param planRunner         P&E 子规划与执行
     * @param childApprovals     单批准槽 + 待批登记
     * @param routerTaskExecutor 并行子执行池
     * @param maTargets          目标方案执行委托（刀5）
     */
    public ChildRunner(TrialBindingLoader loader, ReActTrialExecutor react, TrialMemoryCoordinator memory,
                       PlanTrialExecutor planRunner, ChildPlanApprovalRegistry childApprovals,
                       ThreadPoolTaskExecutor routerTaskExecutor, MaTargetRunner maTargets) {
        this(loader, react, memory, planRunner, childApprovals, routerTaskExecutor, maTargets, null);
    }

    /**
     * @param loader             装配 REACT 子计划、收紧超时
     * @param react              REACT 子执行
     * @param memory             记忆召回与注入统计
     * @param planRunner         P&E 子规划与执行
     * @param childApprovals     单批准槽 + 待批登记
     * @param routerTaskExecutor 并行子执行池
     * @param maTargets          目标方案执行委托（刀5）
     * @param workflowTargets    目标工作流执行委托（刀5B）；可空，为空时该候选按「已跳过」处理
     */
    public ChildRunner(TrialBindingLoader loader, ReActTrialExecutor react, TrialMemoryCoordinator memory,
                       PlanTrialExecutor planRunner, ChildPlanApprovalRegistry childApprovals,
                       ThreadPoolTaskExecutor routerTaskExecutor, MaTargetRunner maTargets,
                       WorkflowTargetRunner workflowTargets) {
        this.loader = loader;
        this.react = react;
        this.memory = memory;
        this.planRunner = planRunner;
        this.childApprovals = childApprovals;
        this.routerTaskExecutor = routerTaskExecutor;
        this.maTargets = maTargets;
        this.workflowTargets = workflowTargets;
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
        return runChildren(parent, intents, modelUser, userText, userId, sessionId, deadlineNanos, progress, trace,
                childParent, isolateChild, false);
    }

    /**
     * 同 {@link #runChildren(AgentDef, List, String, String, long, long, long, TrialProgress, ObsTraceScope,
     * Long, boolean)}；多一个「本轮是否允许该叶子输出可点选项块」。
     *
     * <p>为什么只有单意图才可能开：选项块会让用户停下来拍板，多意图并行时每个子都抛一组选项，
     * 用户无从选择；串行交接同样整链不开。该开关只影响系统提示里是否追一段平台锁定说明，
     * 不改变分派、顺序、静音与汇总口径。
     *
     * @param leafChoice true 表示调用方（工作台）允许本轮唯一叶子输出选项块
     * @return 与 intents 同序的子结果
     */
    public List<ChildOutcome> runChildren(AgentDef parent, List<RoutedIntent> intents, String modelUser,
                                          String userText, long userId, long sessionId, long deadlineNanos,
                                          TrialProgress progress, ObsTraceScope trace, Long childParent,
                                          boolean isolateChild, boolean leafChoice) {
        if (intents.size() == 1) {
            /*
             * 单意图内联执行，不经过线程池；submitter 传当前线程即可（清理分支本就不会走到）。
             * 同时标记「本层只派了一位」：嵌套方案要在这条链上建停等边界，多子链一旦停住，
             * 快照里没有其它成员的坐标，续跑会把它们整段丢掉。
             */
            boolean previousSole = SoleChainHolder.allows();
            SoleChainHolder.set(true);
            try {
                return List.of(runOneChild(parent, intents.get(0), modelUser, userText, userId, sessionId,
                        deadlineNanos, progress, trace, childParent, false, isolateChild, Thread.currentThread(),
                        null, null, leafChoice));
            } finally {
                SoleChainHolder.restore(previousSole);
            }
        }
        // 嵌套编排（命中 MA 候选后的那一层）不抢同一个池：按序内联推进，
        // 避免「池线程等子任务、子任务排队等线程」的自等待；结果与顺序与并行一致
        if (MaNestedContext.inside()) {
            return runChildrenInline(parent, intents, modelUser, userText, userId, sessionId, deadlineNanos,
                    progress, trace, childParent, isolateChild);
        }
        List<Future<ChildOutcome>> futures = new ArrayList<>(intents.size());
        // 并行子跑池线程：ThreadLocal 不跨线程继承，必须由提交线程把同一块草稿板带进 worker
        ScratchBoard scratch = ScratchContext.board();
        // 本轮素材同理：worker 读不到根线程的 EntryMaterialHolder，必须在提交前取快照显式带进去
        String material = EntryMaterialHolder.current();
        try {
            Thread submitter = Thread.currentThread();
            for (RoutedIntent intent : intents) {
                futures.add(routerTaskExecutor.submit(
                        () -> ScratchContext.callAs(scratch, scratchOwner(intent), () ->
                                EntryMaterialHolder.callAs(material, () ->
                                        runOneChild(parent, intent, modelUser, userText, userId, sessionId,
                                                deadlineNanos, progress, trace, childParent, true, isolateChild,
                                                submitter, null)))));
            }
            List<ChildOutcome> outcomes = new ArrayList<>(intents.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    RouterClock.assertBefore(deadlineNanos);
                    long remainMs = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                    outcomes.add(futures.get(i).get(remainMs, TimeUnit.MILLISECONDS));
                } catch (TrialFailure ex) {
                    // 父墙钟已到：尽量打断未完成子
                    cancelPending(futures);
                    throw ex;
                } catch (InterruptedException ex) {
                    // 请求线程被中断：先恢复中断标记再按失败收束，避免吞掉停机/取消信号
                    Thread.currentThread().interrupt();
                    cancelPending(futures);
                    outcomes.add(ChildOutcome.failed(intents.get(i), "子 Agent 执行被中断"));
                } catch (Exception ex) {
                    /*
                     * 单个子失败（含它自己撞上 consecutive_fail_abort）只记这一路失败，
                     * <b>不</b>取消其它仍在跑的子：熔断按专家分开，前一个专家把自己打断，
                     * 后一个专家仍要能读文件、写稿。取消统一交给 finally（只清未完成的）。
                     */
                    // ExecutionException 需剥掉包装层才能拿到业务异常；中断/超时按原样处理
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    /*
                     * 停等不是失败：并行链理论上不会建边界（SoleChainHolder 为 false），
                     * 但更深一层若真的抛出来，必须原样交给入口，绝不能被压成「子 Agent 执行失败」。
                     */
                    if (cause instanceof MaPauseSignal pause) {
                        cancelPending(futures);
                        throw pause;
                    }
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
     * 嵌套编排下的多子执行：<b>并行</b>跑，但不用 {@code routerTaskExecutor}。
     *
     * <p>为什么曾经是串行：目标方案已经运行在外层子线程上，再向同一个池提交会在高并发时
     * 形成「池线程等子任务、子任务排队等线程」的自等待。当时的处置是「当前线程按序逐个跑」，
     * 结果把 <b>ROUTER_PARALLEL</b>（含固定并行）语义悄悄改成了串行 ——
     * 用户看到角标写着「路由并行」，泳道上却是「制度专家跑完报告专家才起步」。
     *
     * <p>现在改为：<b>在独立的小并发里并行推进</b>，既不回到外层池（不自等待），
     * 也不牺牲并行语义。并发度取 {@link #INLINE_PARALLELISM}（默认 4，够覆盖一层方案的常见子数），
     * 每轮都用完即关，不共享、不驻留。
     *
     * @param parent        编排根
     * @param intents       已排序的参与者
     * @param modelUser     已注入记忆的用户侧文本
     * @param userText      用户原文
     * @param deadlineNanos 父墙钟截止
     * @param progress      可空
     * @param trace         可空
     * @param childParent   子 AGENT 的 parent_id
     * @param isolateChild  多意图时 true：子仅吃 sub_query
     * @return 与 intents 同序的子结果
     */
    private List<ChildOutcome> runChildrenInline(AgentDef parent, List<RoutedIntent> intents, String modelUser,
                                                 String userText, long userId, long sessionId, long deadlineNanos,
                                                 TrialProgress progress, ObsTraceScope trace, Long childParent,
                                                 boolean isolateChild) {
        /*
         * 只并行、不换道：目标方案的子必须真正并发（否则「路由并行」名不副实）。
         * 用独立线程池而不是外层 routerTaskExecutor，避免父线程占池 + 子任务排队 = 自等待。
         * 单子时不必开池，直接当前线程跑。
         */
        if (intents.size() <= 1) {
            return runSequentialInline(parent, intents, modelUser, userText, userId, sessionId, deadlineNanos,
                    progress, trace, childParent, isolateChild);
        }
        ExecutorService lane = Executors.newFixedThreadPool(
                Math.min(intents.size(), INLINE_PARALLELISM), inlineWorkerFactory());
        // 多子并行不是单链：本层显式标 false，避免外层单链的标记被内部成员误读成「可以停等」
        boolean previousSole = SoleChainHolder.allows();
        SoleChainHolder.set(false);
        try {
            List<Future<ChildOutcome>> futures = new ArrayList<>(intents.size());
            ScratchBoard scratch = ScratchContext.board();
            /*
             * 嵌套上下文同样不跨线程：池线程默认 depth=0，会让方案内层子专家的 id 退化回根层，
             * 与根层同名 route_key 撞车。这里把「深度 + 方案键」显式带进任务体，作用域与任务一致。
             */
            int nestedDepth = MaNestedContext.depth();
            String nestedParentKey = MaNestedContext.parentKey();
            // 与草稿板同理：素材也是根线程的 ThreadLocal，泳道线程必须显式带进去
            String material = EntryMaterialHolder.current();
            Thread submitter = Thread.currentThread();
            for (RoutedIntent intent : intents) {
                futures.add(lane.submit(() -> MaNestedContext.callAt(nestedDepth, nestedParentKey, () ->
                        ScratchContext.callAs(scratch, scratchOwner(intent), () ->
                                EntryMaterialHolder.callAs(material, () ->
                                        runOneChild(parent, intent, modelUser, userText, userId, sessionId,
                                                deadlineNanos, progress, trace, childParent, true, isolateChild,
                                                submitter, null))))));
            }
            List<ChildOutcome> outcomes = new ArrayList<>(intents.size());
            for (int i = 0; i < futures.size(); i++) {
                RouterClock.assertBefore(deadlineNanos);
                try {
                    long remainMs = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                    outcomes.add(futures.get(i).get(remainMs, TimeUnit.MILLISECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    cancelPending(futures);
                    outcomes.add(ChildOutcome.failed(intents.get(i), "子 Agent 执行被中断"));
                } catch (Exception ex) {
                    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                    log.warn("嵌套并行子执行失败 sessionId={} index={} type={} cause={}",
                            sessionId, i, cause.getClass().getSimpleName(), CauseDigest.of(cause), cause);
                    outcomes.add(ChildOutcome.failed(intents.get(i),
                            UserFacingText.safeMessage(cause, "子 Agent 执行失败")));
                }
            }
            return outcomes;
        } finally {
            // 本层专属池：用完即关，不残留线程；已在跑的任务允许跑完（不打断正常收尾）
            lane.shutdown();
            SoleChainHolder.restore(previousSole);
        }
    }

    /**
     * 嵌套编排下的串行执行（单子或显式串行链）：当前线程按 intents 顺序逐个跑。
     *
     * @param parent        编排根
     * @param intents       已排序的参与者
     * @param modelUser     已注入记忆的用户侧文本
     * @param userText      用户原文
     * @param deadlineNanos 父墙钟截止
     * @param progress      可空
     * @param trace         可空
     * @param childParent   子 AGENT 的 parent_id
     * @param isolateChild  多意图时 true：子仅吃 sub_query
     * @return 与 intents 同序的子结果
     */
    private List<ChildOutcome> runSequentialInline(AgentDef parent, List<RoutedIntent> intents, String modelUser,
                                                   String userText, long userId, long sessionId, long deadlineNanos,
                                                   TrialProgress progress, ObsTraceScope trace, Long childParent,
                                                   boolean isolateChild) {
        List<ChildOutcome> outcomes = new ArrayList<>(intents.size());
        for (RoutedIntent intent : intents) {
            RouterClock.assertBefore(deadlineNanos);
            try {
                outcomes.add(runOneChild(parent, intent, modelUser, userText, userId, sessionId, deadlineNanos,
                        progress, trace, childParent, false, isolateChild, Thread.currentThread(), null));
            } catch (MaPauseSignal pause) {
                // 停等不是失败：单链停止由入口落等待态，这里不能压成「子 Agent 执行失败」
                throw pause;
            } catch (RuntimeException ex) {
                // 与并行链同口径：单子失败不打断其它子，正文走边界清洗
                log.warn("嵌套编排子执行失败 agentId={} type={} cause={}",
                        rootId(parent), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                outcomes.add(ChildOutcome.failed(intent, UserFacingText.safeMessage(ex, "子 Agent 执行失败")));
            }
        }
        return outcomes;
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
        return runFixedSequence(parent, modelUser, userText, userId, sessionId, deadlineNanos, progress, trace,
                childParent, intents, runtime, null, List.of(), null, false);
    }

    /**
     * 串行执行（可停等）：固定串行与路由后串行共用。
     *
     * <p>停等只在 {@code pauseScope != null && leafChoice} 时发生：某一步的模型产出了
     * 可点询问（{@code agenthub_choice} / 后端整理），循环立刻抛出 {@link MaPauseSignal}，
     * 把「已完成成员、当前成员、剩余成员、问句选项、已写键」打包成可恢复状态交给入口落库。
     * 不在这里写库、也不在这里收口参与者：那位专家的卡片要保持在「等待」态，
     * 等用户回复后由续跑继续把它收成完成态。
     *
     * @param seedPrior             续跑时已完成成员的结论（拼进下一步输入）；首次为空
     * @param firstModelTextOverride 续跑第一位（刚被询问的那位）的模型输入；非空时不再按前置摘要重拼
     * @param leafChoice             本串行链是否允许成员输出可点询问
     * @return 每位成员的结果，顺序与 intents 一致
     */
    public List<ChildOutcome> runFixedSequence(AgentDef parent, String modelUser, String userText, long userId,
                                               long sessionId, long deadlineNanos, TrialProgress progress,
                                               ObsTraceScope trace, Long childParent, List<RoutedIntent> intents,
                                               AgentRuntimeConfig runtime, MaPauseScope pauseScope,
                                               List<FixedSequenceContext.PriorStep> seedPrior,
                                               String firstModelTextOverride, boolean leafChoice) {
        List<ChildOutcome> outcomes = new ArrayList<>(intents.size());
        List<FixedSequenceContext.PriorStep> prior = new ArrayList<>(
                seedPrior == null ? List.of() : seedPrior);
        // 单子串行没有「前置」，与现网单意图对齐：允许继承父历史，否则硬隔离
        boolean single = intents.size() == 1;
        /*
         * 本层是不是单链：只有单链才允许下一层（目标方案）建停等边界。
         * 多子串行一旦停在某一位，快照里没有兄弟步的坐标，续跑只会把被停住的那条链跑完，
         * 其余成员整段丢失——所以这里按本层成员数显式标一次。
         */
        boolean previousSole = SoleChainHolder.allows();
        SoleChainHolder.set(single);
        try {
            for (int index = 0; index < intents.size(); index++) {
                RoutedIntent intent = intents.get(index);
                RouterClock.assertBefore(deadlineNanos);
                // 每步先推本步分块标题；串行下后续 reasoning/tool 自然落进该块，不需要 lane 重推
                if (progress != null && intent.routeLine() != null) {
                    progress.route(intent.routeLine());
                }
                // 多步串行的输入固定为「本步子问 + 前面专家的成文结论」，不看上下文策略开关，
                // 也不把工具 JSON / 父历史灌进去；并行不走本方法。单步交回既有解析。
                String stepModelText = null;
                boolean isolate;
                if (index == 0 && firstModelTextOverride != null && !firstModelTextOverride.isBlank()) {
                    // 续跑第一位：把「子问 + 询问前终答 + 用户回复」一次交给同一位专家，不再重拼前置摘要
                    stepModelText = firstModelTextOverride;
                    isolate = true;
                } else if (!single) {
                    /*
                     * 本步的问题基座取 intent.subQuery()：
                     * - 固定串行里 sub_query 恒等于用户原问（不做切分）；
                     * - 路由后串行里它是判路由切给该子的子问题，不能再拿整段合问。
                     */
                    String stepQuestion = intent.subQuery() == null || intent.subQuery().isBlank()
                            ? userText
                            : intent.subQuery();
                    stepModelText = FixedSequenceContext.render(stepQuestion, prior);
                    isolate = true;
                } else {
                    isolate = !runtime.allowParentHistoryWhenSingleOn();
                }
                ChildOutcome outcome;
                try {
                    outcome = runOneChild(parent, intent, modelUser, userText, userId, sessionId, deadlineNanos,
                            progress, trace, childParent, false, isolate, Thread.currentThread(), stepModelText,
                            null, leafChoice, pauseScope != null);
                } catch (MaPauseSignal pause) {
                    /*
                     * 停等不是失败：这条串行链下面的目标方案停住了，信号必须原样穿出去。
                     * 压成占位段会让外层以为「该成员失败」，用户看到一处假错误、且永远等不到续跑。
                     */
                    throw pause;
                } catch (RuntimeException ex) {
                    // 默认「失败继续」：压成占位段，后续步仍能看到「该步未完成」而不是整条序列中断
                    log.warn("固定串行第 {} 步失败 agentId={} type={} cause={}", outcomes.size() + 1,
                            rootId(parent), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    outcome = ChildOutcome.failed(intent, UserFacingText.safeMessage(ex, "子 Agent 执行失败"));
                }
                outcomes.add(outcome);
                /*
                 * 成员刚问完：整条串行立即挂起。检测放在 outcome 收集之后、下一步之前，
                 * 保证停等快照里能看到这位成员的完整终稿与身份；并行 / 未开放停等的链传的 scope 为 null，
                 * 不会走到这里，行为与现网一致。
                 */
                if (pauseScope != null && leafChoice) {
                    AgentChoice captured = capturedChoice();
                    if (captured != null) {
                        throw buildPauseSnapshot(pauseScope, intents, index, outcomes, captured, sessionId);
                    }
                }
                // 只交接成文结论：展示名不含 route_key；失败步只有一句短说明，不带异常原文和工具 JSON
                prior.add(new FixedSequenceContext.PriorStep(
                        RouterTexts.childLabel(intent.candidate().agent()),
                        outcome.turn() == null ? null : outcome.turn().getAssistantText(),
                        outcome.turn() == null));
            }
            return outcomes;
        } finally {
            SoleChainHolder.restore(previousSole);
        }
    }

    /**
     * 当前请求里模型产出的可点询问。没有开启捕获（调试 / 并行 / 嵌套）时为空。
     *
     * @return 捕获到的选项；没有时为空
     */
    private static AgentChoice capturedChoice() {
        ChoiceCapture capture = ChoiceCaptureHolder.current();
        return capture == null ? null : capture.choice();
    }

    /**
     * 把「停在第 index 位」打包成可恢复快照。
     *
     * <p>done 只放**已经跑完**的成员（含 seedDone），paused 单独放当前询问者；
     * writtenKeys 记录本次停等之后视为已写身份 / 路由的键，续跑时据此避免重复 begin。
     *
     * @param scope     停等边界
     * @param intents   本轮串行意图（含续跑时已跑完的前缀）
     * @param index     停在 intents 的哪一位
     * @param outcomes  与 intents 同序的已执行结果（至少到 index）
     * @param choice    模型给出的问句与选项
     * @param sessionId 会话
     * @return 控制流信号
     */
    private static MaPauseSignal buildPauseSnapshot(MaPauseScope scope, List<RoutedIntent> intents, int index,
                                                    List<ChildOutcome> outcomes, AgentChoice choice,
                                                    long sessionId) {
        MaPauseSnapshot state = new MaPauseSnapshot();
        state.setSessionId(sessionId);
        state.setMaDefId(scope.maDefId());
        state.setSchemeName(scope.schemeName());
        state.setTemplate(scope.template());
        state.setDepth(scope.depth());
        state.setParentKey(scope.parentKey());
        state.setUserText(scope.userText());
        state.setModelUser(scope.modelUser());
        state.setGateway(scope.gateway());
        state.setJudgeReasoning(scope.judgeReasoning());
        state.setDecisionBlock(scope.decisionBlock());
        state.setSkipped(scope.skipped());
        state.setPreWrittenKeys(scope.writtenKeys());
        List<MaPauseMember> done = new ArrayList<>(scope.seedDone());
        // 前缀这一段在上一轮停等时已经写过，再次停等只作续跑摘要，不重复补事件
        state.setSeedDoneSize(done.size());
        // 本次续跑里已经完成的成员（paused 之前）也要进 done，续跑时作为前置摘要与本轮拼接稿
        for (int i = 0; i < index && i < outcomes.size(); i++) {
            done.add(pauseMemberOf(outcomes.get(i), intents.get(i)));
        }
        state.setDone(done);
        MaPauseMember paused = pauseMemberOf(outcomes.get(index), intents.get(index));
        state.setPausedMember(paused);
        state.setPausedAnswer(outcomes.get(index).turn() == null
                ? "" : outcomes.get(index).turn().getAssistantText());
        state.setChoice(choice.awaiting());
        List<MaPauseMember> remaining = new ArrayList<>();
        for (int i = index + 1; i < intents.size(); i++) {
            remaining.add(pauseMemberOf(intents.get(i)));
        }
        state.setRemaining(remaining);
        // 本次停等之后：已完成 + 当前询问者 + 已跳过 + 本次之前已写，都视为已写身份 / 路由
        Set<String> written = new LinkedHashSet<>(scope.writtenKeys());
        for (MaPauseMember member : done) {
            addKey(written, member.getRouteKey());
        }
        addKey(written, paused.getRouteKey());
        for (MaPauseMember skip : scope.skipped()) {
            addKey(written, skip.getRouteKey());
        }
        state.setWrittenKeys(written);
        return new MaPauseSignal(state);
    }

    /**
     * 已执行成员 → 停等快照成员。
     *
     * @param outcome 成员结果
     * @param intent  成员意图
     * @return 快照成员
     */
    private static MaPauseMember pauseMemberOf(ChildOutcome outcome, RoutedIntent intent) {
        MaPauseMember member = pauseMemberOf(intent);
        if (outcome == null) {
            return member;
        }
        member.setAnswer(outcome.turn() == null ? null : outcome.turn().getAssistantText());
        member.setError(outcome.error());
        member.setFailed(!outcome.skipped() && outcome.turn() == null);
        member.setSkipped(outcome.skipped());
        member.setTurn(outcome.turn());
        member.setReasoning(outcome.reasoning());
        member.setWorkflow(outcome.workflow());
        member.setChildren(outcome.children());
        member.setConclusionName(outcome.conclusionName());
        member.setConclusionReasoning(outcome.conclusionReasoning());
        return member;
    }

    /**
     * 只有路由信息的成员（未执行 / 已跳过）。
     *
     * @param intent 成员意图
     * @return 快照成员
     */
    private static MaPauseMember pauseMemberOf(RoutedIntent intent) {
        MaPauseMember member = new MaPauseMember();
        ParticipantView participant = participantOf(intent, intent.candidate());
        member.setRouteKey(intent.candidate().route().getRouteKey());
        member.setParticipantId(participant.id());
        member.setParticipantName(participant.name());
        member.setParticipantKind(participant.kind());
        member.setParticipantDepth(participant.depth());
        member.setSubQuery(intent.subQuery());
        member.setReason(intent.reason());
        member.setRouteLine(intent.routeLine());
        return member;
    }

    /**
     * 供编排层为「超限已跳过」成员构造同一个快照结构。
     *
     * @param intent 已跳过成员意图
     * @return 快照成员（skipped=true，无 turn）
     */
    public static MaPauseMember pauseMemberOfSkipped(RoutedIntent intent) {
        MaPauseMember member = pauseMemberOf(intent);
        member.setSkipped(true);
        member.setError(intent.routeLine());
        return member;
    }

    /**
     * 只收非空路由键；空键不参与去重（否则所有无名成员会被当成同一位）。
     *
     * @param keys 集合
     * @param key  路由键
     */
    private static void addKey(Set<String> keys, String key) {
        if (key != null && !key.isBlank()) {
            keys.add(key.trim());
        }
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
        return runOneChild(parent, intent, modelUser, userText, userId, sessionId, deadlineNanos, progress,
                trace, childParent, parallelWorker, isolateChild, submitter, modelTextOverride, extraSpanAttrs, false);
    }

    /**
     * 同 {@link #runOneChild(AgentDef, RoutedIntent, String, String, long, long, long, TrialProgress,
     * ObsTraceScope, Long, boolean, boolean, Thread, String, Map)}；额外决定本叶子是否允许输出可点选项块。
     *
     * <p>选项块只对「单专家 / 入口直分叶子」开放：多意图并行、串行交接、P&E 子与嵌套方案内层
     * 都不传 true（它们的提示词与旧口径逐字一致）。
     *
     * @param leafChoice true 表示系统提示追一段平台内置的选项块说明
     */
    public ChildOutcome runOneChild(AgentDef parent, RoutedIntent intent, String modelUser, String userText,
                                    long userId, long sessionId, long deadlineNanos, TrialProgress progress,
                                    ObsTraceScope trace, Long childParent, boolean parallelWorker,
                                    boolean isolateChild, Thread submitter, String modelTextOverride,
                                    Map<String, Object> extraSpanAttrs, boolean leafChoice) {
        return runOneChild(parent, intent, modelUser, userText, userId, sessionId, deadlineNanos, progress,
                trace, childParent, parallelWorker, isolateChild, submitter, modelTextOverride, extraSpanAttrs,
                leafChoice, false);
    }

    /**
     * 同 {@link #runOneChild(AgentDef, RoutedIntent, String, String, long, long, long, TrialProgress,
     * ObsTraceScope, Long, boolean, boolean, Thread, String, Map, boolean)}；
     * 额外决定本子产出的选项是否标成「运行正停在这里等用户」。
     *
     * <p>只有可停等串行链传 true；其它入口仍按普通一次性选项落库 / 出帧，行为与现网一致。
     *
     * @param waitingChoice true 表示本子的选项属于一条会挂起的运行
     */
    public ChildOutcome runOneChild(AgentDef parent, RoutedIntent intent, String modelUser, String userText,
                                    long userId, long sessionId, long deadlineNanos, TrialProgress progress,
                                    ObsTraceScope trace, Long childParent, boolean parallelWorker,
                                    boolean isolateChild, Thread submitter, String modelTextOverride,
                                    Map<String, Object> extraSpanAttrs, boolean leafChoice,
                                    boolean waitingChoice) {
        if (trace != null && parallelWorker) {
            // 并行子线程各自 bind trace，并拿到独立 stack（ThreadLocal.withInitial）
            ObsTraceHolder.bind(trace);
            trace.stack();
        }
        Long agentSpan = null;
        // 共享草稿区：本子用同一个 route_key 作为写入归属（并行时谁先写谁占位）
        ScratchContext.bindOwner(scratchOwner(intent));
        // 参与者身份与已包装的出口必须活到 catch：失败 / 中止也要给这条 begin 收口
        ParticipantView self = null;
        TrialProgress childProgress = null;
        try {
            RouterClock.assertBefore(deadlineNanos);
            Candidate hit = intent.candidate();
            // 本轮入口预处理素材（识图 / 抽字 / 转写）。没有预处理的轮次为空串，下面各处的行为与原来逐字一致
            String material = EntryMaterialHolder.current();
            // 多意图：仅 sub_query；单意图：可保留父历史前缀。span 展示一律只用 sub_query
            // 素材单独传进去：sub_query 常是判路由写的一句概述，不能让票面跟着被替换掉
            ChildSubQueryInput.Result childInput = modelTextOverride != null
                    ? new ChildSubQueryInput.Result(
                            ChildSubQueryInput.matchText(modelTextOverride, material), intent.subQuery(), false)
                    : ChildSubQueryInput.resolve(modelUser, userText, intent.subQuery(), isolateChild, material);
            // 串行交接已经把「子问 + 前置结论」拼进 modelTextOverride。
            // 方案 / 工作流目标不走 ReAct 的 modelText，必须把这份文本当成它们的用户问题，否则下一位方案仍只看见原问。
            // 多意图 / 一条消息多问时（isolateChild=true）每个目标只吃自己的子问：
            // 把整条消息交给方案或工作流，会让它们把别的问题也一并处理（等于第二问串味）。
            String handedUser = modelTextOverride != null && !modelTextOverride.isBlank()
                    ? modelTextOverride
                    : (isolateChild ? childInput.modelText() : userText);
            String handedModel = modelTextOverride != null && !modelTextOverride.isBlank()
                    ? modelTextOverride
                    : (isolateChild ? childInput.modelText() : modelUser);
            // 每一位参与者**开跑就先出块**：先推 begin（先于本步任何思考 / 工具），
            // 后续增量再带归属标记；不再在每个增量前重推同一句「路由到」。
            String childRouteLine = intent.routeLine() != null ? intent.routeLine()
                    : RouterTexts.routeSummary(hit, intent.reason());
            /*
             * 身份必须在开跑前算好：没装配进度出口（工作流节点 / 窄测）时也要用它给产物署名，
             * 不能等到「有 progress 才认人」。同一套 id 同时用于直播 begin、落库与回放。
             */
            ParticipantView selfView = participantOf(intent, hit);
            self = selfView;
            TrialProgress childProgressView = progress;
            if (progress != null) {
                if (hit.isMaTarget()) {
                    /*
                     * 方案目标：外层静音只挡本层 token，内层方案的子结论与「方案结论」必须能写出去，
                     * 所以绑在 conclusionChannel 上；begin 在进入内层之前推出，方案块因此立刻出现。
                     */
                    TrialProgress schemeBase = progress.conclusionChannel();
                    childProgressView = schemeBase == null ? null
                            : participantBound(schemeBase, selfView, childRouteLine, waitingChoice);
                    // 路由行只推一次，并且从本方案自己的包装器出去：
                    // 外层若再包着别的方案，不会把这条改挂回去。
                    TrialProgress routeOutlet = childProgressView != null ? childProgressView : progress;
                    routeOutlet.route(childRouteLine);
                } else {
                    childProgressView = participantBound(progress, selfView, childRouteLine, waitingChoice);
                    if ((parallelWorker || MaNestedContext.inside()) && childProgressView != null) {
                        // 并行 / 嵌套：路由从子包装器出去，归属就是这位子专家
                        childProgressView.route(childRouteLine);
                    }
                }
            }
            childProgress = childProgressView;
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
            // 目标方案内部的下一层跳：只有 MA 目标会填，其它分支保持空表
            List<NestedRoute> nested = List.of();
            // 方案自己的分流判定推理：随段一起落库，刷新后方案块的思考框仍在
            String schemeReasoning = null;
            // 目标方案自己的汇总结论（「方案结论：X」标记 + 汇总推理）：不带上就会「结束时汇总那条消失」
            String schemeConclusion = null;
            String schemeConclusionReasoning = null;
            // 工作流目标的执行卡片终态：随本子返回给上层落库（实时帧不依赖它）
            WorkflowRunView workflowView = null;
            if (hit.isMaTarget()) {
                // 刀5：目标是另一套编排方案 → 委托回模板执行器，在<b>同一条会话</b>里再跑一层编排。
                // 不写提示词侧的记忆段：目标方案自己的子 Agent 各自按既有纪律召回。
                /*
                 * childProgress 已在外层绑好「方案」身份（begin + 归属）：方案自己的分流判定推理
                 * 会挂到它这块上，内层子专家再各自 begin 自己的块。
                 */
                MaTargetRunner.MaTargetResult result = maTargets.run(intent, hit.maTarget(), handedUser, handedModel,
                        userId, sessionId, deadlineNanos, childProgress);
                turn = result.turn();
                notices = result.notices() == null ? List.of() : new ArrayList<>(result.notices());
                nested = result.children();
                schemeReasoning = result.reasoning();
                schemeConclusion = result.conclusionName();
                schemeConclusionReasoning = result.conclusionReasoning();
            } else if (hit.isWorkflowTarget()) {
                // 刀5B：目标是已发布工作流 → 交给工作流域的现网运行入口，引擎不并进对话栈。
                // 记忆段同样不写：工作流各节点的 Agent 节点自己按既有纪律召回。
                if (workflowTargets == null) {
                    // 只有在窄测里才会出现「未装配工作流委托」：明确失败，不伪装成空回答
                    throw new TrialFailure("当前实例未装配工作流运行入口，无法运行该工作流线路");
                }
                WorkflowTargetRunner.WorkflowTargetResult result = workflowTargets.run(intent,
                        hit.workflowTarget(), handedUser, userId, sessionId, deadlineNanos, childProgress);
                turn = result.turn();
                notices = result.notices() == null ? List.of() : new ArrayList<>(result.notices());
                // 卡片终态随本子返回：落库后刷新仍能重建时间轴，而不是只剩一段正文
                workflowView = result.view();
            } else if (AgentRunMode.PLAN_AND_EXECUTE.name().equals(hit.agent().getRunMode())) {
                PlanChildResult planned = runPlanChild(parent, hit, intent, childInput, userId, sessionId,
                        deadlineNanos, childProgress);
                turn = planned.turn();
                notices = planned.notices();
            } else {
                // 记忆段在装配时进链（工具目录之前）；召回用「子问 + 素材」，失败自动回落 MySQL。
                // 只喂子问会漏掉票面里的关键词：判路由常把「这张发票什么时候到期」写成「确认用户诉求」，
                // 拿概述去召回想不起「差旅报销」这类偏好。这里只改匹配用的文本，不改抽取与落库口径。
                String matchText = ChildSubQueryInput.matchText(childInput.displaySubQuery(), material);
                TrialMemoryCoordinator.Augmented recalled = memory.recallDurable(
                        memorySession(rootId(parent), userId, sessionId), matchText);
                /*
                 * 只有真正允许出选项块时才走四参重载：多意图 / 串行交接 / 嵌套内层保持原三参调用，
                 * 提示词与既有行为逐字一致（也让既有单测的装配桩继续命中，不必为这条开关改测）。
                 * 第二个入参同时是 Skill 触发词的匹配文本（TrialPromptFactory.skillsOf），
                 * 因此这里传「子问 + 素材」：票面里命中的触发词才能硬注入对应 Skill。
                 */
                TrialPlan plan = leafChoice
                        ? loader.loadReact(hit.agent(), matchText, recalled.memoryBlock(), true)
                        : loader.loadReact(hit.agent(), matchText, recalled.memoryBlock());
                plan.setToolArgsFacts(recalled.injectedFacts());
                memory.publishInjection(plan);
                long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                loader.tightenTimeout(plan, Math.min(plan.getLimits().timeoutMs(), remainingMs));
                turn = react.execute(plan, childInput.modelText(), userId, sessionId, childProgress);
                notices = plan.getNotices();
            }
            RouterClock.assertBefore(deadlineNanos);
            // 产物在产生处定作者：本子写出的文件记在本参与者名下，内层专家已带身份的不覆盖。
            // 这样直播帧、轮次落库与刷新回放读的是同一个作者，文件不会先挂工作流卡再改挂。
            turn = stampFileOwners(turn, self);
            if (trace != null) {
                SpanClose agentOk = new SpanClose();
                agentOk.setStatus(ObsStatus.OK);
                agentOk.setInputText(childInput.displaySubQuery());
                agentOk.setOutputText("执行完成");
                // 真专家仍是「执行子 Agent」且不打种类；方案 / 工作流外壳另标，供追踪页改标签
                boolean schemeShell = hit.isMaTarget();
                boolean workflowShell = hit.isWorkflowTarget();
                agentOk.setAttrsJson(RouterObs.childAgentAttrs(hit.agent(),
                        RouterObs.shellBrief(schemeShell, workflowShell), childInput.fallback(),
                        RouterObs.withShellRole(extraSpanAttrs, schemeShell, workflowShell)));
                trace.close(agentSpan, agentOk);
            }
            /*
             * 工作流目标的正文载体是它自己的执行卡片（适配层按节点完成顺序增量推 answer），
             * 这里不再补一条 assistant：否则同一段文字会既进卡片、又进一条专家气泡，
             * 回放还会按「###」把它当成多人拼接稿切开。
             *
             * 其余目标：正文出口会在本子结束处把整段结论钉回该参与者；
             * 已经按增量流过正文的路径默认空操作，不会重影。
             * 必须走子包装器：直接打在外层 progress 上，出口补出的正文会被外层改挂成方案。
             * 方案目标的内部子结论和汇总结论由内层执行自己推，这里再推会把子正文复制进方案块。
             */
            if (!hit.isWorkflowTarget() && !(hit.isMaTarget() && !nested.isEmpty())) {
                deliverConclusion(childProgress != null ? childProgress : progress, childRouteLine, turn);
            }
            /*
             * 开过 begin 的参与者必须自己收口：成功路径统一推一条终态。
             * 工作流卡 / 方案外壳（子专家已各自收口）也在这里结束自己的块，页面不再等整轮收尾才停转。
             * 但串行方案里这位刚产出「可点询问」时不能收口：卡片要留在等待态，
             * 问句与选项嵌在同一张卡里；用户回复后续跑的那一次再统一收成完成态。
             */
            boolean awaitingUser = leafChoice && capturedChoice() != null;
            if (!awaitingUser) {
                closeParticipant(childProgress != null ? childProgress : progress, self,
                        TrialProgress.PARTICIPANT_DONE, "");
            }
            // 工作流目标：卡片终态随结果交给上层落库；其它分支 workflowView 保持 null
            if (workflowView != null) {
                return ChildOutcome.okWorkflow(intent, notices, turn, workflowView);
            }
            // 目标方案真汇总过：结论名与汇总推理一起交出去，落库后回放才画得出汇总那条
            if (schemeConclusion != null) {
                return ChildOutcome.okScheme(intent, notices, turn, nested, schemeReasoning,
                        schemeConclusion, schemeConclusionReasoning);
            }
            return ChildOutcome.ok(intent, notices, turn, nested, schemeReasoning);
        } catch (MaPauseSignal pause) {
            /*
             * 停等不是失败：目标方案在自己的串行链里停住了。
             * 这里既不写失败帧、也不把卡片收成 FAILED——那张卡要停在「正在等用户回复」，
             * 由入口落等待态、用户回复后从同一位继续。span 按正常暂停收尾，追踪页不出现假错误。
             */
            if (trace != null && agentSpan != null) {
                SpanClose paused = new SpanClose();
                paused.setStatus(ObsStatus.OK);
                paused.setOutputText("等待用户回复");
                trace.close(agentSpan, paused);
            }
            throw pause;
        } catch (RuntimeException ex) {
            // 子 Agent 失败：并行时被压成失败段（用户可见文案走清洗），但栈必须留在日志里
            log.warn("子 Agent 执行失败 agentId={} parallel={} type={} cause={}",
                    rootId(parent), parallelWorker, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            if (trace != null && agentSpan != null) {
                SpanClose agentErr = new SpanClose();
                agentErr.setStatus(ObsStatus.ERROR);
                agentErr.setErrorSummary(ex.getMessage());
                agentErr.setOutputText(ex.getMessage());
                // 失败外壳同样带种类，避免出错的工作流仍被标成 Agent；真专家文案保持「子 Agent 失败」。
                // hit 在 try 内声明，这里用入参再取一次，失败发生在取候选之前时不加种类。
                Candidate failedHit = intent == null ? null : intent.candidate();
                boolean schemeShell = failedHit != null && failedHit.isMaTarget();
                boolean workflowShell = failedHit != null && failedHit.isWorkflowTarget();
                String failBrief = workflowShell ? "工作流失败" : (schemeShell ? "方案失败" : "子 Agent 失败");
                String errAttrs = ObsAttrs.withCause(ObsAttrs.withBrief(null, failBrief), CauseDigest.of(ex));
                Map<String, Object> role = RouterObs.shellRoleFields(schemeShell, workflowShell);
                if (!role.isEmpty()) {
                    errAttrs = ObsAttrs.withFields(errAttrs, role);
                }
                agentErr.setAttrsJson(errAttrs);
                trace.close(agentSpan, agentErr);
            }
            // 并行时单子失败不抛出，写成失败段；单子路径保持抛出（与现网一致）
            String failText = UserFacingText.safeMessage(ex, "子 Agent 执行失败");
            /*
             * 失败 / 中止也要收口：没有这一帧，前端只能等整轮结束才把这张卡从「进行中」放下来，
             * 中途一直是转圈的空壳。状态区分「自身错误」与「被外部打断」。
             */
            String failState = Thread.currentThread().isInterrupted()
                    || (ex instanceof TrialFailure failure && LimitHitReasons.TIMEOUT.equals(failure.getFinishReason()))
                    ? TrialProgress.PARTICIPANT_ABORTED : TrialProgress.PARTICIPANT_FAILED;
            closeParticipant(childProgress != null ? childProgress : progress, self, failState, failText);
            if (parallelWorker) {
                // 失败段正文会进终答拼接：边界处清洗，避免路径/端口/异常码被复述给用户
                return ChildOutcome.failed(intent, failText);
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
                    rootId(parent), child.getId(), routeKey, sessionId);
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
     * 子 AGENT 挂点：只认父是本层 ROUTE 的那条 LLM；没有则退回本层 ROUTE。
     * 不读 trace 上「最近一条 LLM」——嵌套或并行外壳会把它改成别的层的模型步。
     *
     * @param trace     当前埋点；可空
     * @param routeSpan 本层 ROUTE 行 id；可空
     * @return 非根挂点；trace/route 皆空时为 null
     */
    public static Long childAgentParent(ObsTraceScope trace, Long routeSpan) {
        if (trace != null && routeSpan != null) {
            Long judgeLlm = trace.llmUnder(routeSpan);
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
     * 编排根主键：方案试跑没有 Agent 根，返回 null；旧 Agent 试跑返回 Agent 主键。
     *
     * @param parent 旧 Agent 试跑的 ROUTER；可为 null
     * @return Agent 主键或 null
     */
    private static Long rootId(AgentDef parent) {
        return parent == null ? null : parent.getId();
    }

    /**
     * 嵌套并行用的线程工厂：守护线程 + 可读线程名。
     *
     * <p>用守护线程：这是本层临时池，停机时不应因某个子还在等模型响应而拖住 JVM 退出；
     * 线程名带 {@code ma-inline-} 前缀，排障时能与 {@code router-task-} 区分开。
     *
     * @return 线程工厂
     */
    private static ThreadFactory inlineWorkerFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "ma-inline-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 参与者结束后交出成文结论。
     *
     * <p>流式路径的默认实现是空操作（正文已经按 token 出去了）。
     * 静音包装器覆写后会先钉路由行再推整段；**空正文也会调用**——
     * 出口据此给「一个字都没产出」的参与者补一句终态说明，卡片才不会一直转圈。
     *
     * @param progress  本子拿到的进度出口（可能是静音包装）
     * @param routeLine 本子路由行
     * @param turn      本子终答；空正文也照样交出（由出口决定补什么）
     */
    private static void deliverConclusion(TrialProgress progress, String routeLine, TrialTurn turn) {
        if (progress == null) {
            return;
        }
        String text = turn == null ? "" : turn.getAssistantText();
        progress.deliverParticipantConclusion(routeLine, text == null ? "" : text.trim());
    }

    /**
     * 给参与者推一条终态：done / failed / aborted。
     *
     * <p>出口缺失（窄测 / 工作流节点内）时什么都不做；出口抛异常不得影响本子结果，
     * 状态帧是展示旁路，失败了只记 debug。
     *
     * @param progress 本子出口；可空
     * @param self     本子身份；可空
     * @param state    {@link TrialProgress#PARTICIPANT_DONE} 等
     * @param note     终态说明；可空
     */
    private static void closeParticipant(TrialProgress progress, ParticipantView self, String state, String note) {
        if (progress == null || self == null) {
            return;
        }
        try {
            progress.endParticipant(self, state, note);
        } catch (RuntimeException ex) {
            log.debug("参与者终态未写出 participantId={} state={} type={}",
                    self.id(), state, ex.getClass().getSimpleName());
        }
    }

    /**
     * 把一位参与者的增量绑上身份：开跑时先推 begin，之后每次转发前声明归属。
     *
     * <p>为什么不再像旧实现那样在每个增量前重推「路由到」：那条是用户可见文案，
     * 每帧重推会让方案自己的思考与子专家的事件互相打断（页面表现为串块）。
     * 现在身份走独立的 participant 帧（不产生可见文字），文案只推一次。
     *
     * @param progress      下游推送
     * @param participant   本参与者身份
     * @param routeLine     本子路由行；仅用于归属前的可见依据（由调用方单独推一次）
     * @param waitingChoice true 表示本子属于可停等串行链：它产出的选项要标成「等待用户」，
     *                      前端据此把下一句当作回复而不是新一轮提问
     * @return 包装后的推送；progress 为空时返回 null
     */
    private TrialProgress participantBound(TrialProgress progress, ParticipantView participant, String routeLine,
                                           boolean waitingChoice) {
        if (progress == null) {
            return null;
        }
        // 先声明「这一步开始」：前端据此立刻出块，先于任何思考 / 工具
        progress.beginParticipant(participant);
        String id = participant.id();
        return new TrialProgress() {
            /**
             * 这次增量归本包装器。内层已经声明过时只转发，不再改挂成自己。
             *
             * @param forward 交给下游的动作
             */
            private void emit(Runnable forward) {
                NestedAttribution.emit(() -> progress.attributeParticipant(id), forward);
            }

            @Override
            public void phase(String phase) {
                progress.phase(phase);
            }

            @Override
            public void route(String summary) {
                emit(() -> progress.route(summary));
            }

            @Override
            public void reasoning(String delta) {
                emit(() -> progress.reasoning(delta));
            }

            /** 汇总这类「先推标记再推思考」的增量同样要带归属，但不再重推泳道文案。 */
            @Override
            public void reasoningPinned(String delta) {
                emit(() -> progress.reasoningPinned(delta));
            }

            /** 更深一层的参与者（方案内部子专家）开始：必须原样透传，否则内层块出不来。 */
            @Override
            public void beginParticipant(ParticipantView nested) {
                progress.beginParticipant(nested);
            }

            /** 更深一层的归属切换：原样透传，重叠的 id 由下游实现去重。 */
            @Override
            public void attributeParticipant(String participantId) {
                progress.attributeParticipant(participantId);
            }

            @Override
            public void toolStart(String name) {
                emit(() -> progress.toolStart(name));
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
                emit(() -> progress.tool(name, arguments, result, success));
            }

            @Override
            public void assistant(String delta) {
                emit(() -> progress.assistant(delta));
            }

            /** 参与者整段结论：先切回本子，正文才会挂到这个名字下。 */
            @Override
            public void deliverParticipantConclusion(String conclusionLine, String text) {
                emit(() -> progress.deliverParticipantConclusion(conclusionLine, text));
            }

            /**
             * 参与者终态：原样透传，但先切回本子。
             *
             * <p>为什么必须覆写：默认实现是空操作，漏了它「开过 begin 的块」永远收不了口，
             * 页面只能等整轮 done 才把所有卡一起放下来——正是「汇总落屏、前面空壳同时闪现」。
             * 不在这里补自己的 done：收口时机由上层（成功收尾 / 失败分支）统一给，
             * 避免同一位参与者被推两条终态。
             */
            @Override
            public void endParticipant(ParticipantView finished, String state, String note) {
                ParticipantView who = finished == null ? participant : finished;
                emit(() -> progress.endParticipant(who, state, note));
            }

            /** 内层方案的收口通道不绕过归属控制：外层自己会在需要时再包一层。 */
            @Override
            public TrialProgress conclusionChannel() {
                return progress;
            }

            /**
             * 产物：先切回本子再透传，并在产生处补上作者。
             *
             * <p>工具回调只拿得到文件名与体积；直播帧若不带身份，前端只能按
             * 「最后一位参与者」猜宿主，文件就会先挂到工作流卡上再改挂。
             * 这里补的是同一份身份，落库侧由 {@code stampFileOwners} 再确保一次。
             */
            @Override
            public void file(GeneratedFile file) {
                GeneratedFile owned = file == null ? null : file.withOwner(id, participant.name());
                emit(() -> progress.file(owned));
            }

            /**
             * 结构化选项：与文件同一套归属纪律——先切回本子再透传，
             * chips 才会挂到这一位专家的气泡下面，而不是上位块。
             */
            @Override
            public void choice(AgentChoice choice) {
                // 可停等串行链：直播帧也标 waiting，前端才能把下一句识别成对这位专家的回复
                AgentChoice payload = waitingChoice && choice != null ? choice.awaiting() : choice;
                emit(() -> progress.choice(payload));
            }

            /**
             * 工作流执行卡片：先切回本子再透传，卡片才会挂在这一位工作流名下。
             *
             * <p>工作流没有 token，卡片是它在消息流里的**唯一可见载体**；
             * 漏掉这个覆写会让整张卡片在实时流里凭空消失（落库回放却有）。
             */
            @Override
            public void workflow(WorkflowRunView view) {
                emit(() -> progress.workflow(view));
            }

            /** 子计划待批也是本子的事件：先切回本子再透传。 */
            @Override
            public void childPlanPending(ChildPlanPendingView pending) {
                emit(() -> progress.childPlanPending(pending));
            }
        };
    }

    /**
     * 落库用的身份：与开跑时 {@link #participantOf} 同一套 id / kind / routeKey。
     *
     * <p>必须在组装时间线分段时调用（嵌套深度与 begin 当时一致）。
     * 跳过、没有候选时返回 null，不给没开跑的人补一张卡。
     *
     * @param intent 子意图；可空
     * @return 参与者身份；无法识别时 null
     */
    public static ParticipantView viewOf(RoutedIntent intent) {
        if (intent == null || intent.candidate() == null) {
            return null;
        }
        return participantOf(intent, intent.candidate());
    }

    /**
     * 取一次子调用的参与者身份。
     *
     * <p>类型决定前端角标与分块：方案（MA）单列一块、工作流只报状态、其余按专家。
     *
     * <p>id 必须带「深度 + 父方案路由键 + 本子路由键」：两层方案都分了同名子专家（如 policy）时，
     * 只用 {@code p-policy} 会被实时写侧的 {@code participants.putIfAbsent} 当成同一个人，
     * 第二张卡永远收不到 begin，页面就只能等收尾回放补。同一套算法用于直播、落库与回放，
     * 不在前端补 id。
     *
     * @param intent 子意图（含 route_key）
     * @param hit    命中的候选
     * @return 参与者身份
     */
    private static ParticipantView participantOf(RoutedIntent intent, Candidate hit) {
        String key = intent.candidate().route().getRouteKey();
        String trimmedKey = key == null ? "" : key.trim();
        String leaf = !trimmedKey.isEmpty() ? trimmedKey : commonIdOf(hit);
        String id = participantIdOf(MaNestedContext.depth(), MaNestedContext.parentKey(), leaf);
        String name = RouterTexts.childLabel(hit.agent());
        int depth = MaNestedContext.depth();
        if (hit.isMaTarget()) {
            return ParticipantView.scheme(id, name, key, depth);
        }
        if (hit.isWorkflowTarget()) {
            return new ParticipantView(id, name, ParticipantView.KIND_WORKFLOW, key, depth);
        }
        return ParticipantView.agent(id, name, key, depth);
    }

    /**
     * 拼参与者稳定 id：{@code p-<depth>-<父方案键|root>-<叶子键>}。
     *
     * <p>父方案键来自 {@link MaNestedContext} 的栈顶；根层没有父方案时固定写 root，
     * 保证格式统一、可复现，也避免出现 {@code p-0--policy} 这种双横线。
     *
     * <p>公开给停等补写复用：续跑 / 停等时要用<b>同一个</b>函数算出方案卡 id，
     * 否则回放里会多出一张挂不上父的方案卡。
     *
     * @param depth     嵌套层数
     * @param parentKey 当前层方案路由键；根层为空
     * @param leafKey   本子路由键（没有 route_key 时用 Agent 主键）
     * @return 同一轮内唯一的参与者 id
     */
    public static String participantIdOf(int depth, String parentKey, String leafKey) {
        int safeDepth = Math.max(0, depth);
        String parent = parentKey == null || parentKey.isBlank() ? "root" : parentKey.trim();
        String leaf = leafKey == null || leafKey.isBlank() ? "unknown" : leafKey.trim();
        return "p-" + safeDepth + "-" + parent + "-" + leaf;
    }

    /**
     * 没有 route_key 时的叶子键：Agent 主键优先，保证直播与回放算出同一个 id。
     *
     * @param hit 命中的候选
     * @return 非空叶子键
     */
    private static String commonIdOf(Candidate hit) {
        Long agentId = hit.agent() == null ? null : hit.agent().getId();
        return agentId == null ? "unknown" : String.valueOf(agentId);
    }

    /**
     * 给本子写出的产物署名（直播帧在包装器里已带，这里补轮次落库用的那一份）。
     *
     * <p>只补空身份：内层专家先署名的文件，外层方案不能再改写成自己；
     * 历史 / 工具标记产生的无主文件按本子身份补齐，回放时才能挂回正确的卡。
     *
     * @param turn  本子结果；可空
     * @param owner 本子参与者身份；可空
     * @return 同一个 turn（便于链式调用）
     */
    private static TrialTurn stampFileOwners(TrialTurn turn, ParticipantView owner) {
        if (turn == null || owner == null) {
            return turn;
        }
        List<GeneratedFile> files = turn.getFiles();
        if (files == null || files.isEmpty()) {
            return turn;
        }
        List<GeneratedFile> stamped = new ArrayList<>(files.size());
        boolean changed = false;
        for (GeneratedFile file : files) {
            if (file == null) {
                continue;
            }
            GeneratedFile next = file.withOwner(owner.id(), owner.name());
            // withOwner 在已有身份时返回自身：按引用判断是否真的补过，避免无谓 setFiles
            changed = changed || next != file;
            stamped.add(next);
        }
        if (changed) {
            turn.setFiles(stamped);
        }
        return turn;
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

package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.RouterAnswerMode;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.agent.domain.service.AgentCriticPolicy;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.runtime.application.router.AggregateOutcome;
import com.agenthub.runtime.application.router.AnswerAggregator;
import com.agenthub.runtime.application.router.BlockedCandidate;
import com.agenthub.runtime.application.router.Candidate;
import com.agenthub.runtime.application.router.Candidates;
import com.agenthub.runtime.application.router.ChildOutcome;
import com.agenthub.runtime.application.router.ChildRunner;
import com.agenthub.runtime.application.router.MaNestedContext;
import com.agenthub.runtime.application.router.MaTargetRunner;
import com.agenthub.runtime.application.router.NestedRoute;
import com.agenthub.runtime.application.router.RouteSpan;
import com.agenthub.runtime.application.router.RoutedIntent;
import com.agenthub.runtime.application.router.RouterClock;
import com.agenthub.runtime.application.router.RouterObs;
import com.agenthub.runtime.application.router.RouterTexts;
import com.agenthub.runtime.application.router.WorkflowTargetRunner;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.RouteHint;
import com.agenthub.runtime.domain.model.RouteSticky;
import com.agenthub.runtime.domain.model.ScratchBoard;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.application.router.EntrySchemePreference;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.ChildSubQueryInput;
import com.agenthub.runtime.domain.service.CompeteVerdictParser;
import com.agenthub.runtime.domain.service.CriticPrompt;
import com.agenthub.runtime.domain.service.CriticVerdictParser;
import com.agenthub.runtime.domain.service.FixedSequenceContext;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.RouteDecisionParser;
import com.agenthub.runtime.domain.service.RouteKeywordPicker;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.ScratchContext;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.agenthub.runtime.application.router.ChildOutcome.hasAnswer;
import static com.agenthub.runtime.application.router.ChildOutcome.keyOf;

/**
 * 一层 Router 试跑执行器。所属域：runtime。
 *
 * <p>按 {@code runtime_config_json.maTemplate} 早分支六套拓扑：
 * <ul>
 *   <li>{@code ROUTER_PARALLEL}：判路由输出有序 [{route_key, sub_query}] → ≥2 子并行 ReAct → 终答 CONCAT/SUMMARIZE/VOTE；</li>
 *   <li>{@code FIXED_PARALLEL}：跳过判路由，全部启用候选吃同一份用户原问并行执行（超上限按序跳过）；</li>
 *   <li>{@code FIXED_SEQUENCE}：跳过判路由，按 sort_order 串行 S1→S2→…，后步可见前置终答摘要；</li>
 *   <li>{@code CRITIC_LOOP}：跳过判路由，恰好 1 个执行者出稿 → 评审专家判定 → 有限回修，
 *       终答＝最后一版执行者输出（不拼接、不汇总）。</li>
     *   <li>{@code ROUTER_THEN_SEQUENCE}：判路由契约与 ROUTER_PARALLEL 相同，但命中子按<b>执行顺序</b>
 *       串行 S1→S2→…（复用固定串行的前置摘要纪律），最后 CONCAT/SUMMARIZE/VOTE。</li>
 *   <li>{@code COMPETE}：跳过判路由，全部启用候选吃同一份用户原问并行作答，再按
 *       {@code competeStrategy}（JUDGE_PICK / FIRST_SUCCESS）选优，终答＝赢家正文。</li>
 * </ul>
 * <p>终答汇总三种语义：{@code CONCAT} 拼接 / {@code SUMMARIZE} 父模型整合 / {@code VOTE} 评选模型投票选一路原文；
 * 后两者失败都降级 CONCAT。COMPETE 的 PICK 属于模板语义，与汇总策略不是一回事。
 * 多意图子执行强制隔离上下文（仅 sub_query，见 {@link ChildSubQueryInput}）；单意图可继承父历史。
 * 思考过程按子分段推送/落库，禁止收束拍扁成单条路由摘要。一子失败其它继续。
 * 观测挂点：根 ROUTE → （判路由模板：判路由 LLM）→（子 AGENT∥…/→…）→ 子内；子 AGENT 禁止挂 ROUTE/根。
 */
@Component
public class RouterTrialExecutor {

    private static final Logger log = LoggerFactory.getLogger(RouterTrialExecutor.class);

    /** 竞争选优给评委看的单个子终答长度硬顶：超过就截断，避免一次评选吃掉父墙钟 */
    private static final int COMPETE_ANSWER_LIMIT = 3000;

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
    /** 终答汇总（CONCAT / SUMMARIZE / VOTE）与判路由模型解析 */
    private final AnswerAggregator answers;
    /** 子 Agent 执行（并行扇出 / 串行推进 / P&E 批准冒泡） */
    private final ChildRunner children;
    /** 工作流目标执行委托（刀5B）；正常由 runtime.infra 适配器实现，窄测可为空 */
    private final WorkflowTargetRunner workflowTargets;

    /**
     * 兼容构造：未装配工作流委托（窄测沿用）。
     *
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
                               ThreadPoolTaskExecutor routerTaskExecutor) {
        this(routes, agents, loader, react, prompts, limitResolver, memory, planRunner, childApprovals,
                routerTaskExecutor, null);
    }

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
     * @param workflowTargets    工作流目标执行委托（刀5B）；为空时工作流候选按「已跳过」处理
     */
    @Autowired
    public RouterTrialExecutor(AgentRouteRepository routes, AgentDefRepository agents,
                               TrialBindingLoader loader, ReActTrialExecutor react, SystemPromptAssembler prompts,
                               RuntimeLimitResolver limitResolver, TrialMemoryCoordinator memory,
                               PlanTrialExecutor planRunner, ChildPlanApprovalRegistry childApprovals,
                               @Qualifier("routerTaskExecutor") ThreadPoolTaskExecutor routerTaskExecutor,
                               WorkflowTargetRunner workflowTargets) {
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
        this.workflowTargets = workflowTargets;
        // 汇总（CONCAT/SUMMARIZE/VOTE）与判路由模型解析由协作者承担；构造器签名保持 10 参不变
        this.answers = new AnswerAggregator(loader, prompts, react);
        // 子执行同样在构造器内装配：模板执行器只保留「选谁、按什么顺序跑」的编排决策；
        // 刀5 命中 MA 候选时用 this::runMaTarget 回到本执行器，刀5B 工作流候选走注入的实现
        this.children = new ChildRunner(loader, react, memory, planRunner, childApprovals, routerTaskExecutor,
                this::runMaTarget, workflowTargets);
    }

    /**
     * @param parent    已启用的 ROUTER
     * @param userText  用户原文
     * @param userId    平台用户
     * @param sessionId 试跑会话
     * @return 路由说明、子 Agent 的装配提示和执行结果
     */
    public RoutedTurn execute(AgentDef parent, String userText, long userId, long sessionId) {
        return execute(parent, null, userText, userText, userId, sessionId, null);
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
        return execute(parent, null, userText, modelUser, userId, sessionId, progress);
    }

    /**
     * 按方案执行一轮编排（MA-P2 刀2 起的主入口）。
     *
     * <p>读源规则：{@code spec} 非空时模板、上下文策略、候选与判路由模型全部来自 {@code ma_def}；
     * 为空时保持旧口径（从 {@code parent.runtime_config_json} + {@code agent_route} 读）。
     *
     * @param parent    旧 Agent 试跑的 ROUTER；方案运行传 {@code null}（方案以 ma_def 自己为根）
     * @param spec      方案执行来源；可空
     * @param userText  用户原文（判路由与子输入都从它出发）
     * @param modelUser 已注入记忆的用户侧文本；无记忆方案通常等于原文
     * @param progress  可空
     * @return 路由说明、子 Agent 的装配提示和执行结果
     */
    public RoutedTurn execute(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                              long sessionId, TrialProgress progress) {
        return execute(parent, spec, userText, modelUser, userId, sessionId, progress, null);
    }

    /**
     * 带线路提示的一轮编排（刀3：工作台沿用 / 强制换线）。
     *
     * <p>{@code hint} 只影响判路由提示词与「强制命中」两个分支：
     * <ul>
     *   <li>传 {@code null}（调试 / 试跑）时行为与刀3 之前逐字节一致；</li>
     *   <li>有沿用线路时只在提示词里说明「优先沿用」，判路由仍可改派；</li>
     *   <li>有强制线路且命中候选时跳过判路由；未命中则回落正常判路由并留一条可见说明。</li>
     * </ul>
     *
     * @param parent    旧 Agent 试跑的 ROUTER；方案运行传 {@code null}
     * @param spec      方案执行来源；可空
     * @param userText  用户原文
     * @param modelUser 已注入记忆的用户侧文本
     * @param progress  可空
     * @param hint      线路提示；可空
     * @return 路由说明、分段、终答与粘性回写指令
     */
    public RoutedTurn execute(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                              long sessionId, TrialProgress progress, RouteHint hint) {
        EffectiveLimits parentLimits = limitResolver.resolve(parent);
        // 方案优先：模板与上下文策略以 ma_def.config_json 为准，避免执行时又读回 Agent 镜像
        AgentRuntimeConfig runtime = spec != null
                ? spec.runtime()
                : AgentRuntimeConfig.parse(parent.getRuntimeConfigJson(), parent.getRunMode());
        // 共享草稿区（MA-P1 刀7）：只有选中 SHARED_SCRATCH 才开板；板是「本轮 run 级」状态，
        // 挂在线程上下文里随根执行传下去，根结束（成功 / 失败 / 取消）后 close 丢弃，不落库、不跨轮复用
        ScratchBoard scratch = runtime.scratchOn() ? new ScratchBoard() : null;
        if (scratch == null) {
            return executeRun(parent, spec, userText, modelUser, userId, sessionId, progress, runtime, parentLimits,
                    hint);
        }
        try {
            return ScratchContext.callAs(scratch, "", () ->
                    executeRun(parent, spec, userText, modelUser, userId, sessionId, progress, runtime, parentLimits,
                            hint));
        } finally {
            // 取消 / 异常同样走到这里：停止接受新写，已写入内容本轮内仍可读，下一轮直接消失
            scratch.close();
        }
    }

    /**
     * 命中 MA 候选后，在同一条会话里跑目标方案（刀5）。所属域：runtime。
     *
     * <p>为什么复用本执行器的 {@link #execute}：目标方案就是另一份 {@code ma_def}，
     * 它自己的模板 / 候选 / 判路由模型与顶层方案完全一样；
     * 重写一条「跑别人方案」的平行实现只会让两套口径慢慢漂移。
     *
     * <p>硬边界：
     * <ul>
     *   <li><b>同会话</b>：{@code sessionId} 原样带下去，时间线 / 记忆 / 计量不换栈，不跳调试页；</li>
     *   <li><b>只一层</b>：目标方案的执行来源在装载时已剔除 MA 候选，这里再遇到也不会继续下探；</li>
     *   <li><b>不写线路</b>：外层线路由调用方回写；目标方案内部的路由不出本方法；</li>
     *   <li><b>不带提示</b>：{@code hint} 恒传 null，外层手动换线不穿透。</li>
     * </ul>
     *
     * @param intent       命中的子意图（route_key / 子问题）
     * @param target       目标方案执行来源
     * @param userText     用户原文
     * @param modelUser    已注入记忆的用户侧文本
     * @param userId       平台用户
     * @param sessionId    当前会话
     * @param deadlineNanos 外层墙钟截止
     * @param progress     可空；目标方案的子增量继续推给同一条时间线
     * @return 目标方案终答与时间线说明
     * @throws TrialFailure 目标方案不存在 / 目标方案执行失败
     */
    private MaTargetRunner.MaTargetResult runMaTarget(RoutedIntent intent, MaRunSpec target, String userText,
                                                      String modelUser, long userId, long sessionId,
                                                      long deadlineNanos, TrialProgress progress) {
        if (target == null) {
            throw new TrialFailure("目标编排方案不存在或已停用，请重新选择线路");
        }
        // 入口 → 方案 → 专家只进入一层；再往下套方案，到第 4 层才熔断
        if (MaNestedContext.depth() >= AgentRuntimeConfig.MAX_MA_NEST_DEPTH) {
            throw new TrialFailure(AgentRuntimeConfig.NEST_DEPTH_REJECTED);
        }
        RouterClock.assertBefore(deadlineNanos);
        ObsTraceScope trace = ObsTraceHolder.current();
        // 目标方案以自己为根：退出时要把父挂点还原，
        // 否则本轮后续的汇总 / 投票 LLM 会挂到编排根而不是 ROUTE
        Long previousParent = trace == null ? null : trace.implicitParent();
        MaNestedContext.enter();
        try {
            // parent 传 null：目标方案不需要任何 Agent 根，执行器按 spec 装配
            RoutedTurn nested = execute(null, target, userText, modelUser, userId, sessionId, progress, null);
            List<String> notices = new ArrayList<>();
            // 目标方案内部每一跳原样带出去：时间线按「方案 → 各子专家」逐块展示，
            // 不再压成一条「子编排内部：路由到…」的说明句（那会让参与者全部隐身）
            List<NestedRoute> children = new ArrayList<>();
            if (nested.segments() != null) {
                for (RouteSegment segment : nested.segments()) {
                    children.add(new NestedRoute(segment.routeLine(), segment.turn(), segment.error()));
                }
            }
            if (nested.notices() != null) {
                notices.addAll(nested.notices());
            }
            return new MaTargetRunner.MaTargetResult(nested.turn(), notices, children);
        } finally {
            MaNestedContext.exit();
            if (trace != null) {
                trace.bindParent(previousParent);
            }
        }
    }

    /**
     * 模板早分支与三条执行链的观测壳。所属域：runtime。
     *
     * <p>为什么单独一层：方案以自己为根，需要在所有模板之外再开一个「编排根」步骤；
     * 放在这里一层，六条模板分支就不必各自记住开关根步骤，执行语义（模板 → 判路由 →
     * 子 Agent → 汇总）原样保留在 {@link #executeTemplates}。
     *
     * @param parent       旧 Agent 试跑的 ROUTER；方案试跑传 null
     * @param spec         方案来源；可空（空＝旧 Agent 配置）
     * @param userText     用户原文
     * @param modelUser    已注入记忆的用户侧文本
     * @param progress     可空
     * @param runtime      已归一化的运行配置
     * @param parentLimits 父墙钟与轮次上限
     * @param hint         线路提示；可空（调试 / 试跑恒空）
     * @return 路由说明、分段与终答
     */
    private RoutedTurn executeRun(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                                  long sessionId, TrialProgress progress, AgentRuntimeConfig runtime,
                                  EffectiveLimits parentLimits, RouteHint hint) {
        // 方案以自己为根：开一个只读「编排根」步骤，泳道才有根，attrs 才能带 ma.def_id / code / template
        ObsTraceScope trace = ObsTraceHolder.current();
        Long maRootSpan = null;
        if (trace != null && spec != null) {
            maRootSpan = trace.open(SpanType.AGENT, RouterObs.maRootName(spec), null, null, null);
            trace.bindParent(maRootSpan);
        }
        RuntimeException failure = null;
        try {
            return executeTemplates(parent, spec, userText, modelUser, userId, sessionId, progress, runtime,
                    parentLimits, hint);
        } catch (RuntimeException ex) {
            // 虚拟根编排根同样要收尾：失败帧带脱敏 cause，泳道第一行就能定位
            failure = ex;
            throw ex;
        } finally {
            RouterObs.closeMaRoot(trace, maRootSpan, spec, failure);
        }
    }

    /**
     * 六模板分派与判路由主链（虚拟根 / 绑根共用）。所属域：runtime。
     *
     * <p>本方法只管执行语义，编排根的观测壳由 {@link #executeRun} 负责，
     * 因此这里不再重复开关根步骤，父子链与拆前完全一致。
     */
    private RoutedTurn executeTemplates(AgentDef parent, MaRunSpec spec, String userText, String modelUser,
                                        long userId, long sessionId, TrialProgress progress,
                                        AgentRuntimeConfig runtime, EffectiveLimits parentLimits, RouteHint hint) {
        // 模板早分支：评审环与固定模板都不判路由，整条执行链与判路由路径完全分开，避免语义互相污染
        if (runtime.isCriticLoop()) {
            return executeCriticLoop(parent, spec, userText, userId, sessionId, progress, runtime, parentLimits);
        }
        // 竞争选优：同题并发 → 选优取 1 个子终答；不判路由、不做多段拼接
        if (runtime.isCompete()) {
            return executeCompete(parent, spec, userText, modelUser, userId, sessionId, progress, runtime,
                    parentLimits);
        }
        if (runtime.isFixedTemplate()) {
            return executeFixed(parent, spec, userText, modelUser, userId, sessionId, progress, runtime,
                    parentLimits);
        }
        // 路由后串行：判路由契约与 ROUTER_PARALLEL 完全相同，只是把扇出换成「按执行顺序串行」
        if (runtime.isRouterThenSequence()) {
            return executeRouterThenSequence(parent, spec, userText, modelUser, userId, sessionId, progress,
                    runtime, parentLimits);
        }
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, "judge", rootId(parent));
        try {
            assertParentDeadline(deadlineNanos);
            Candidates loaded = loadCandidates(rootId(parent), runtime, spec);
            List<Candidate> candidates = loaded.usable();
            List<BlockedCandidate> blockedCandidates = loaded.blocked();
            if (candidates.isEmpty()) {
                throw new TrialFailure(blockedCandidates.isEmpty()
                        ? "Router 没有可用的子 Agent"
                        : "没有可用的子 Agent：" + blockedCandidates.size()
                                + " 位专家未获本编排允许（" + blockedReasons(blockedCandidates)
                                + "），请检查编排策略与对应 Agent 配置");
            }
            RoutedChoice choice = choose(parent, spec, parentLimits, runtime, userText, candidates,
                    userId, sessionId, deadlineNanos, hint);
            List<RoutedIntent> intents = choice.intents();
            // 关多意图：只保留第一项（强制线路恒为单项，已被 choose 收敛，不在此重复截断）
            if (!runtime.multiIntentOn() && intents.size() > 1) {
                intents = List.of(intents.get(0));
            }
            // 改派 / 手动换线的可见说明：进 SYSTEM 事件（思考区），不打断正文
            List<String> routeNotices = routeNotices(choice, hint, candidates, intents);
            List<String> routeLines = new ArrayList<>();
            for (RoutedIntent intent : intents) {
                routeLines.add(RouterTexts.routeSummary(intent.candidate(), intent.reason()));
            }
            String decisionJson = RouteDecisionParser.toDecisionJson(intents.stream()
                    .map(item -> new RouteDecisionParser.Item(
                            item.candidate().route().getRouteKey() == null ? "" : item.candidate().route().getRouteKey(),
                            item.subQuery()))
                    .toList());
            // 多意图：先推完整决策块；各子「路由到」改在子开跑时推，避免收束后只剩一条糊在一起的摘要
            if (progress != null) {
                if (intents.size() >= 2) {
                    progress.route(RouterTexts.decisionLine(intents));
                } else if (!routeLines.isEmpty()) {
                    progress.route(routeLines.get(0));
                }
            }
            Map<String, Object> routeFields = new LinkedHashMap<>();
            routeFields.put("ma.template", runtime.maTemplateOrDefault());
            routeFields.put("ma.aggregate", runtime.answerModeEnum().name());
            // 上下文策略只落扁平 attrs，不动父链
            routeFields.put("ma.context", runtime.maContextPolicyOrDefault());
            // 绑根没有合成根步骤：方案标识补在这里，两条入口都能按 ma.def_id 检索
            routeFields.putAll(RouterObs.maAttrs(spec));
            // 共享草稿只写键数 / 冲突次数（有则显示），不写任何 key / value 正文
            routeFields.putAll(RouterObs.scratchAttrs());
            route.ok(String.join("；", routeLines), decisionJson,
                    intents.size() == 1 ? intents.get(0).candidate().agent().getId() : null,
                    intents.size() <= 1 ? "路由到 " + childLabel(intents.get(0).candidate().agent())
                            : "多意图路由×" + intents.size(),
                    routeFields);

            // 多子或 SUMMARIZE / VOTE：子侧仍推 tool/reasoning；屏蔽子 assistant，
            // 等齐后由汇总那一步统一出正文（SUMMARIZE 已是流式推送，见 AnswerAggregator）
            boolean muteChildAssistant = intents.size() > 1
                    || runtime.answerModeEnum() == RouterAnswerMode.SUMMARIZE
                    // VOTE 也要等投票结论出来才能推终答，不能让子正文先流出去
                    || runtime.answerModeEnum() == RouterAnswerMode.VOTE;
            // 路由列表≥2 强制隔离上下文；单意图继承父历史（兼容追问）
            boolean isolateChild = intents.size() >= 2;
            TrialProgress childProgress = muteChildAssistant ? muteAssistant(progress, true) : progress;
            // 先链后扇：子 AGENT 挂判路由 LLM，禁止挂 ROUTE/根；无 LLM 时退 ROUTE
            Long childParent = ChildRunner.childAgentParent(trace, route.id());
            List<ChildOutcome> outcomes = children.runChildren(parent, intents, modelUser, userText, userId, sessionId,
                    deadlineNanos, childProgress, trace, childParent, isolateChild);
            // 路由侧说明先写：换线 / 改派要排在本轮子结果之前，时间线顺序才符合因果
            List<String> notices = new ArrayList<>(routeNotices);
            List<RouteSegment> segments = new ArrayList<>();
            for (int i = 0; i < outcomes.size(); i++) {
                ChildOutcome outcome = outcomes.get(i);
                notices.addAll(outcome.notices());
                String line = i < routeLines.size() ? routeLines.get(i)
                        : RouterTexts.routeSummary(outcome.intent().candidate(), outcome.intent().reason());
                segments.add(new RouteSegment(line, outcome.turn(), outcome.error(), outcome.children()));
            }
            /*
             * 未允许被编排调用的子不参与判路由，但必须在时间线留「已跳过」：
             * 只写 routeLine + notice，不塞进 outcomes，避免改变终答拼接与流式推送口径。
             */
            for (ChildOutcome blocked : blockedOutcomes(blockedCandidates, userText)) {
                notices.addAll(blocked.notices());
                segments.add(new RouteSegment(blocked.intent().routeLine(), null, null));
            }
            AggregateOutcome aggregate = answers.finishAnswer(parent, spec, parentLimits, runtime, userText, outcomes, intents,
                    userId, sessionId, deadlineNanos, progress, trace, route.id());
            // 投票结论也要进时间线：winner 一行 + 降级 / 跳过投票的中文说明
            applyAggregate(segments, notices, aggregate);
            TrialTurn turn = aggregate.turn();
            String decisionBlock = intents.size() >= 2 ? RouterTexts.decisionLine(intents) : null;
            // 单意图沿用本条线路；多意图清空（下一轮重判，避免把协作误降级成单人）
            RouteSticky sticky = intents.size() >= 2
                    ? RouteSticky.clear()
                    : RouteSticky.keep(intents.get(0).candidate().route().getRouteKey(),
                            intents.get(0).candidate().agent().getId());
            return new RoutedTurn(String.join("；", routeLines), decisionBlock, segments, notices, turn, sticky);
        } catch (RuntimeException ex) {
            // 路由失败同样是关键 catch：带 Throwable 打全栈，并在 ROUTE 步骤写脱敏 cause
            log.warn("Router 执行失败 parentId={} type={} cause={}",
                    rootId(parent), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            route.error("路由失败", ex);
            throw ex;
        } finally {
            RouteSpan.unbind(trace);
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
    private RoutedTurn executeFixed(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                                    long sessionId, TrialProgress progress, AgentRuntimeConfig runtime,
                                    EffectiveLimits parentLimits) {
        boolean parallel = runtime.isFixedParallel();
        String template = runtime.maTemplateOrDefault();
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, parallel ? "fixed-parallel" : "fixed-sequence", rootId(parent));
        try {
            assertParentDeadline(deadlineNanos);
            Candidates loaded = loadCandidates(rootId(parent), runtime, spec);
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
            // 多子或 SUMMARIZE / VOTE 时屏蔽子 assistant，等齐后再出终答（与判路由路径同口径）
            boolean mute = intents.size() > 1
                    || runtime.answerModeEnum() == RouterAnswerMode.SUMMARIZE
                    || runtime.answerModeEnum() == RouterAnswerMode.VOTE;
            TrialProgress childProgress = mute ? muteAssistant(progress, true) : progress;
            List<ChildOutcome> outcomes = parallel
                    ? children.runChildren(parent, intents, modelUser, userText, userId, sessionId, deadlineNanos,
                    childProgress, trace, route.id(),
                    // 多子强制隔离；单子按 maAllowParentHistoryWhenSingle 决定是否继承父历史
                    intents.size() >= 2 || !runtime.allowParentHistoryWhenSingleOn())
                    : children.runFixedSequence(parent, modelUser, userText, userId, sessionId, deadlineNanos,
                    childProgress, trace, route.id(), intents, runtime);
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
            closeFixedRoute(route, runtime, spec, template, all, decisionJson, parallel);
            List<String> notices = new ArrayList<>();
            List<RouteSegment> segments = new ArrayList<>();
            for (ChildOutcome outcome : all) {
                notices.addAll(outcome.notices());
                // 跳过不是失败：只留「已跳过」路由行，不写成子 Agent 失败
                segments.add(new RouteSegment(outcome.intent().routeLine(), outcome.turn(),
                        outcome.skipped() ? null : outcome.error(), outcome.children()));
            }
            /*
             * 未允许被编排调用的子：只追加路由行 + notice，不进 all，
             * 因此不参与终答拼接、不计 token，也不会触发额外的 assistant 推送。
             */
            for (ChildOutcome blocked : blockedOutcomes(blockedCandidates, userText)) {
                notices.addAll(blocked.notices());
                segments.add(new RouteSegment(blocked.intent().routeLine(), null, null));
            }
            AggregateOutcome aggregate = answers.finishAnswer(parent, spec, parentLimits, runtime, userText, all, intents,
                    userId, sessionId, deadlineNanos, progress, trace, route.id());
            // 投票结论进时间线 + SYSTEM：winner 一行、降级 / 跳过投票说明
            applyAggregate(segments, notices, aggregate);
            TrialTurn turn = aggregate.turn();
            List<String> lines = segments.stream().map(RouteSegment::routeLine).toList();
            return new RoutedTurn(String.join("；", lines), null, segments, notices, turn, null);
        } catch (RuntimeException ex) {
            log.warn("固定编排执行失败 parentId={} template={} type={} cause={}",
                    rootId(parent), template, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            route.error(parallel ? "固定并行失败" : "固定串行失败", ex);
            throw ex;
        } finally {
            RouteSpan.unbind(trace);
        }
    }

    /**
     * 竞争选优执行（{@code COMPETE}）。所属域：runtime。
     *
     * <p>拓扑固定为 <b>同题并行 → 选优 → 终答</b>：
     * <ul>
     *   <li>跳过判路由 LLM：每个启用候选都拿<b>同一份用户原问</b>作答，不做意图切分；</li>
     *   <li>并行纪律与 {@code FIXED_PARALLEL} 完全一致：复用 {@link #runChildren}，超
     *       {@code maxRouteChildren} 的候选按序标 SKIPPED_QUOTA，单子失败不阻其它子；</li>
     *   <li>选优策略由 {@code competeStrategy} 决定：
     *       {@code JUDGE_PICK}（默认，评选模型选 1 个赢家）/{@code FIRST_SUCCESS}（第一个成功子）；</li>
     *   <li>终答只取赢家正文，不做多段拼接；token 与 traces 仍汇总全部子（每个子都真实消耗了额度）。</li>
     * </ul>
     *
     * <p>降级（写死）：评选模型缺失 / 调用失败（非超时）/ 给出非法赢家时，
     * 只要还有成功子就降级 {@code FIRST_SUCCESS}，并在时间线写 SYSTEM 说明；
     * 全部子失败才整次 FAIL。超时仍按统一口径 FAIL，不把墙钟耗尽伪装成成功。
     *
     * @param parent       绑根的 ROUTER；虚拟根为合成的只读壳
     * @param spec         方案执行来源；可空
     * @param userText     用户原文（所有候选共用）
     * @param modelUser    已注入记忆的用户侧文本
     * @param progress     可空；子正文被静音，等齐后只推赢家终答
     * @param runtime      已解析的运行配置（模板、策略、并行上限从这里读）
     * @param parentLimits 父墙钟与轮次上限
     * @return 终答＝赢家正文；segments 保留每个候选的标题与选优结论
     */
    private RoutedTurn executeCompete(AgentDef parent, MaRunSpec spec, String userText, String modelUser,
                                      long userId, long sessionId, TrialProgress progress,
                                      AgentRuntimeConfig runtime, EffectiveLimits parentLimits) {
        String strategy = runtime.competeStrategyOrDefault();
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, "compete", rootId(parent));
        try {
            assertParentDeadline(deadlineNanos);
            Candidates loaded = loadCandidates(rootId(parent), runtime, spec);
            List<Candidate> enabled = loaded.usable();
            List<BlockedCandidate> blockedCandidates = loaded.blocked();
            // 竞争选优没有「判路由兜底」：少于 2 个可用候选就不是竞争，必须中文 FAIL
            if (enabled.size() < AgentRuntimeConfig.MIN_COMPETE_CANDIDATES) {
                throw new TrialFailure(competeTooFewText(enabled.size(), blockedCandidates));
            }
            int cap = runtime.maxRouteChildrenOrDefault();
            // 并行上限小于 2 与「至少 2 个选手」相矛盾：点明配置问题，而不是偷偷只跑一个
            if (cap < AgentRuntimeConfig.MIN_COMPETE_CANDIDATES) {
                throw new TrialFailure("竞争选优的并行上限至少为 " + AgentRuntimeConfig.MIN_COMPETE_CANDIDATES
                        + "，当前为 " + cap + "：请到编排策略里调高并行上限");
            }
            List<Candidate> picked = enabled.size() > cap
                    ? new ArrayList<>(enabled.subList(0, cap))
                    : enabled;
            List<RoutedIntent> intents = new ArrayList<>(picked.size());
            for (int i = 0; i < picked.size(); i++) {
                // 同题：sub_query 恒为用户原问全文，防止某个子只拿到半句话导致比较不公平
                intents.add(new RoutedIntent(picked.get(i), userText, "竞争选优",
                        competeRouteLine(picked.get(i), i + 1, picked.size())));
            }
            List<RoutedIntent> skipped = new ArrayList<>();
            for (int i = cap; i < enabled.size(); i++) {
                Candidate leftover = enabled.get(i);
                skipped.add(new RoutedIntent(leftover, userText, "超出并行上限",
                        "已跳过「" + childLabel(leftover.agent()) + "」：超出竞争并行上限 " + cap));
            }
            if (progress != null) {
                progress.route(competePlanLine(intents.size(), skipped.size(), cap, strategy));
            }
            // 决策块写「参赛者 route_key + 同题」：泳道按序对齐子块，不依赖 sub_query 文本匹配
            String decisionJson = RouteDecisionParser.toDecisionJson(intents.stream()
                    .map(item -> new RouteDecisionParser.Item(
                            item.candidate().route().getRouteKey() == null ? ""
                                    : item.candidate().route().getRouteKey(),
                            item.subQuery()))
                    .toList());
            // 多子竞争必然多段正文：静音 token，结束时按参与者各推一次结论，不再额外推一份赢家重影
            TrialProgress childProgress = muteAssistant(progress, true);
            List<ChildOutcome> outcomes = children.runChildren(parent, intents, modelUser, userText, userId, sessionId,
                    deadlineNanos, childProgress, trace, route.id(),
                    // 同题竞争必须隔离：子只看用户原问，互相不可见，保证比较公平
                    true);
            List<ChildOutcome> successes = new ArrayList<>();
            for (ChildOutcome outcome : outcomes) {
                if (hasAnswer(outcome)) {
                    successes.add(outcome);
                }
            }
            // 全部子失败：不拿占位段伪装成功
            if (successes.isEmpty()) {
                throw new TrialFailure("竞争选优的全部子 Agent 均未完成，请检查子 Agent 配置或稍后重试");
            }
            CompetePick pick = pickCompeteWinner(parent, spec, strategy, userText, successes, userId, sessionId,
                    deadlineNanos, parentLimits, progress, trace, route.id());
            String winnerText = pick.outcome().turn().getAssistantText();
            // 终答只取赢家正文；token / traces 仍按全部子汇总，反映真实消耗
            TrialTurn turn = answers.mergeTurns(outcomes, winnerText == null ? "" : winnerText);
            // 各参赛者的结论已经按人推过；这里再推赢家会把同一段正文再贴一次
            closeCompeteRoute(route, runtime, spec, strategy, outcomes, pick, decisionJson);
            List<String> notices = new ArrayList<>();
            if (pick.degraded()) {
                notices.add(pick.note());
            }
            List<RouteSegment> segments = new ArrayList<>();
            for (ChildOutcome outcome : outcomes) {
                notices.addAll(outcome.notices());
                // 跳过不是失败：只留「已跳过」行，不写成子 Agent 失败
                String line = outcome == pick.outcome()
                        ? outcome.intent().routeLine() + " · ★ 选中"
                        : outcome.intent().routeLine();
                segments.add(new RouteSegment(line, outcome.turn(), outcome.skipped() ? null : outcome.error(),
                        outcome.children()));
            }
            for (RoutedIntent skip : skipped) {
                segments.add(new RouteSegment(skip.routeLine(), null, null));
            }
            /*
             * 未允许被编排调用的子：只追加路由行 + notice，不进 outcomes，
             * 因此不参与选优、不计 token，也不会触发额外的 assistant 推送。
             */
            for (ChildOutcome blocked : blockedOutcomes(blockedCandidates, userText)) {
                notices.addAll(blocked.notices());
                segments.add(new RouteSegment(blocked.intent().routeLine(), null, null));
            }
            // 选优结论单独一行（含策略 / winner / 理由），时间线与泳道都能一眼看到为什么是它
            segments.add(new RouteSegment(pick.routeLine(), null, null));
            List<String> lines = segments.stream().map(RouteSegment::routeLine).toList();
            return new RoutedTurn(String.join("；", lines), null, segments, notices, turn, null);
        } catch (RuntimeException ex) {
            log.warn("竞争选优执行失败 parentId={} type={} cause={}",
                    rootId(parent), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            route.error("竞争选优失败", ex);
            throw ex;
        } finally {
            RouteSpan.unbind(trace);
        }
    }

    /**
     * 选优：按策略取赢家。所属域：runtime。
     *
     * <p>{@code FIRST_SUCCESS} 直接取<b>候选顺序</b>第一个成功子（不是完成最快的那个），
     * 保证同一份配置结果可复现；{@code JUDGE_PICK} 走评选模型，任何失败都降级到前者。
     *
     * @param parent        编排根
     * @param spec          方案来源；可空
     * @param strategy      已归一的策略码
     * @param userText      用户原问
     * @param successes     至少 1 个有正文的子结果，顺序即候选顺序
     * @param deadlineNanos 父墙钟截止
     * @param progress      可空
     * @param trace         可空
     * @param routeSpan     ROUTE 步骤 id；可空
     * @return 赢家 + 策略 + 说明；degraded=true 表示发生过降级
     */
    private CompetePick pickCompeteWinner(AgentDef parent, MaRunSpec spec, String strategy, String userText,
                                          List<ChildOutcome> successes, long userId, long sessionId,
                                          long deadlineNanos, EffectiveLimits parentLimits, TrialProgress progress,
                                          ObsTraceScope trace, Long routeSpan) {
        ChildOutcome first = successes.get(0);
        if (AgentRuntimeConfig.COMPETE_STRATEGY_FIRST_SUCCESS.equals(strategy)) {
            return new CompetePick(first, AgentRuntimeConfig.COMPETE_STRATEGY_FIRST_SUCCESS,
                    "按策略「首个成功子」直接出终答", false,
                    "选优：策略 FIRST_SUCCESS，winner=" + keyOf(first));
        }
        Optional<Long> judgeModel = resolveRouterModel(parent, spec);
        if (judgeModel.isEmpty()) {
            return degradeToFirst(first, "未配置评选模型（maJudgeModelInstanceId / maJudgeModel），已按「首个成功子」出终答");
        }
        try {
            assertParentDeadline(deadlineNanos);
            long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            EffectiveLimits judgeLimits = parentLimits.withTimeoutMs(Math.min(parentLimits.timeoutMs(), remainingMs));
            if (progress != null) {
                progress.route("竞争选优：评选模型比较 " + successes.size() + " 个子终答…");
            }
            if (trace != null) {
                // 评选 LLM 挂在 ROUTE 下，与判路由 / 汇总 LLM 同级
                trace.bindParent(routeSpan);
            }
            String reply = react.judge(
                    MeterScope.console(rootId(parent), judgeModel.get(), sessionId, userId),
                    competePrompt(rootName(parent, spec), userText, successes), competeUserText(userText, successes), judgeLimits);
            Optional<CompeteVerdictParser.Verdict> verdict = CompeteVerdictParser.parse(reply,
                    successes.stream().map(item -> keyOf(item)).toList());
            if (verdict.isEmpty()) {
                return degradeToFirst(first, "评选模型未给出合法赢家（" + keyOf(first) + " 兜底）");
            }
            String winnerKey = verdict.get().winnerKey();
            ChildOutcome winner = successes.stream()
                    .filter(item -> winnerKey.equalsIgnoreCase(keyOf(item)))
                    .findFirst()
                    .orElse(first);
            String reason = verdict.get().reason().isBlank() ? "评选模型未给出理由" : verdict.get().reason();
            return new CompetePick(winner, AgentRuntimeConfig.COMPETE_STRATEGY_JUDGE_PICK, reason, false,
                    "选优：策略 JUDGE_PICK，winner=" + keyOf(winner) + "，理由：" + reason);
        } catch (TrialFailure ex) {
            // 墙钟超时是统一硬拦，不降级：否则父时间预算已被吃掉，后面的推送也不可信
            if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                throw ex;
            }
            log.warn("竞争选优评委调用失败 sessionId={} err={}", sessionId, ex.getMessage(), ex);
            return degradeToFirst(first, "评选模型调用失败，已按「首个成功子」出终答");
        } catch (RuntimeException ex) {
            log.warn("竞争选优评委调用异常 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            return degradeToFirst(first, "评选模型调用异常，已按「首个成功子」出终答");
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
    }

    /**
     * 降级到「首个成功子」并给出可读说明。
     *
     * @param first 候选顺序第一个成功子
     * @param note  中文说明，进 notices 与时间线
     * @return 降级后的选优结论
     */
    private static CompetePick degradeToFirst(ChildOutcome first, String note) {
        return new CompetePick(first, AgentRuntimeConfig.COMPETE_STRATEGY_FIRST_SUCCESS, note, true,
                "选优降级：" + note + "（winner=" + keyOf(first) + "）");
    }

    /**
     * 竞争选优的评委提示词：只要求输出 JSON，不做多轮辩论。
     *
     * @param rootName 编排根名（方案名 / 旧 Agent 名）；可空
     */
    private String competePrompt(String rootName, String userText, List<ChildOutcome> successes) {
        StringBuilder prompt = new StringBuilder();
        // 平台前缀沿用 ROUTER 用途模板，避免和判路由 / 汇总两套前缀并存；
        // 装配器异常 / 未装配时返回 null，不能把评委提示词整段弄崩
        String assembled = prompts.buildRoot(rootName, userText);
        if (assembled != null && !assembled.isBlank()) {
            prompt.append(assembled).append("\n\n");
        }
        prompt.append("你是竞争选优的评委。下面有 ").append(successes.size())
                .append(" 位专家针对同一个用户问题各自给出的最终答复。\n")
                .append("请选出整体最准确、最完整、最符合用户意图的一个。\n")
                .append("只输出 JSON 对象：{\"winner\":\"<route_key>\",\"reason\":\"不超过 30 字的中文理由\"}。\n")
                .append("不要解释、不要 markdown、不要提及专家姓名。\n")
                .append("候选：\n");
        for (ChildOutcome outcome : successes) {
            prompt.append("- ").append(keyOf(outcome));
            String intent = outcome.intent().candidate().route().getIntent();
            if (intent != null && !intent.isBlank()) {
                prompt.append('：').append(intent.trim());
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    /**
     * 评委的用户侧正文：用户原问 + 各子终答（单个回答做长度硬顶，防一次评选吃掉全部预算）。
     */
    private String competeUserText(String userText, List<ChildOutcome> successes) {
        StringBuilder text = new StringBuilder();
        text.append("用户原问：\n").append(userText == null ? "" : userText.trim()).append("\n\n");
        text.append("各候选终答：\n");
        for (ChildOutcome outcome : successes) {
            text.append("### [").append(keyOf(outcome)).append("] ").append(RouterTexts.sectionTitle(outcome)).append('\n');
            text.append(TrialTexts.clip(outcome.turn().getAssistantText(), COMPETE_ANSWER_LIMIT)).append("\n\n");
        }
        return text.toString();
    }

    /**
     * 选优子块标题：与固定模板区分，明确「同题作答」。
     */
    private String competeRouteLine(Candidate hit, int index, int total) {
        return serialRouteLine("竞争选优 ", hit, index, total) + " · 同题作答";
    }

    /**
     * 竞争选优的计划行：让用户在子开跑前就知道策略与人数。
     */
    private static String competePlanLine(int picked, int skipped, int cap, String strategy) {
        StringBuilder line = new StringBuilder("竞争选优：")
                .append(picked).append(" 位候选同题并行，策略 ")
                .append(AgentRuntimeConfig.COMPETE_STRATEGY_FIRST_SUCCESS.equals(strategy) ? "首个成功子" : "评选模型选优");
        if (skipped > 0) {
            line.append("；超出并行上限 ").append(cap).append(" 的 ").append(skipped).append(" 位已跳过");
        }
        return line.toString();
    }

    /**
     * 竞争选优收尾：ROUTE span 打 attrs（模板 / 策略 / winner / 降级），泳道据此画「选优」一行。
     */
    private void closeCompeteRoute(RouteSpan route, AgentRuntimeConfig runtime, MaRunSpec spec, String strategy,
                                   List<ChildOutcome> outcomes, CompetePick pick, String decisionJson) {
        int ok = 0;
        int fail = 0;
        for (ChildOutcome outcome : outcomes) {
            if (outcome.error() != null && !outcome.skipped()) {
                fail++;
            } else {
                ok++;
            }
        }
        StringBuilder brief = new StringBuilder("竞争选优×").append(outcomes.size())
                .append(" → ").append(keyOf(pick.outcome()));
        if (fail > 0) {
            brief.append("（失败 ").append(fail).append("）");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ma.template", runtime.maTemplateOrDefault());
        // 竞争选优不是拼接也不是模型汇总：单独一个汇总码，泳道才不会误标成 CONCAT / SUMMARIZE
        fields.put("ma.aggregate", "PICK");
        fields.put("ma.context", runtime.maContextPolicyOrDefault());
        // 同题竞争恒无判路由 LLM：显式打点，避免泳道把评选调用误读成判路由
        fields.put("ma.skip_judge", "true");
        fields.put("ma.compete.strategy", strategy);
        fields.put("ma.compete.strategy_used", pick.strategy());
        fields.put("ma.compete.winner", keyOf(pick.outcome()));
        fields.put("ma.compete.degraded", pick.degraded() ? "true" : "false");
        fields.put("ma.compete.ok", String.valueOf(ok));
        fields.put("ma.compete.fail", String.valueOf(fail));
        // 绑根没有合成根步骤：方案标识补在这里，按 ma.def_id 检索时不会漏
        fields.putAll(RouterObs.maAttrs(spec));
        fields.putAll(RouterObs.scratchAttrs());
        route.ok("竞争选优完成：winner=" + keyOf(pick.outcome()), decisionJson, null, brief.toString(), fields);
    }

    /**
     * 选优结果。所属域：runtime。
     *
     * @param outcome  赢家子结果
     * @param strategy 实际生效的策略（降级后是 FIRST_SUCCESS）
     * @param note     结论说明（赢家理由或降级原因）
     * @param degraded 是否发生过降级；true 时上层额外写一条 SYSTEM 说明
     * @param routeLine 时间线一行（含 winner）
     */
    private record CompetePick(ChildOutcome outcome, String strategy, String note, boolean degraded,
                               String routeLine) {
    }

    /**
     * 路由后串行执行（{@code ROUTER_THEN_SEQUENCE}）。所属域：runtime。
     *
     * <p>与判路由路径（{@link #execute(AgentDef, String, String, long, long, TrialProgress)}）
     * <b>共用同一份 JUDGE 契约</b>（同一份提示词与解析器），差别只在扇出方式：
     * 命中结果按候选的<b>执行顺序</b>串行接力；判定数组只决定选中了谁。
     *
     * <p>关键纪律：
     * <ul>
     *   <li>判路由不可省：父模型缺失 / 调用失败 / 无有效 route_key 一律整次 FAIL；</li>
     *   <li>后一步输入＝本步子问 + 前置终答摘要（{@code PRIOR_SUMMARY_ONLY} 为默认），
     *       摘要硬顶复用 {@link FixedSequenceContext}（单步 2000 / 累计 6000 字符）；</li>
     *   <li>子步跑在 {@link #runFixedSequence} 上，因此「部分失败继续」与 P&E 单槽冒泡自动继承；</li>
     *   <li>选中人数超过 {@code maxRouteChildren} 时只跑执行顺序前 N，其余标 SKIPPED_QUOTA；</li>
     *   <li>汇总复用 CONCAT / SUMMARIZE（{@link AnswerAggregator#finishAnswer}），不另造汇总器。</li>
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
    private RoutedTurn executeRouterThenSequence(AgentDef parent, MaRunSpec spec, String userText, String modelUser,
                                                 long userId, long sessionId, TrialProgress progress,
                                                 AgentRuntimeConfig runtime, EffectiveLimits parentLimits) {
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, "router-then-sequence", rootId(parent));
        try {
            assertParentDeadline(deadlineNanos);
            Candidates loaded = loadCandidates(rootId(parent), runtime, spec);
            List<Candidate> candidates = loaded.usable();
            // M4：本模板先判路由再串行，判路由本身没有「降级兜底」，零可用候选必须整次 FAIL
            if (candidates.isEmpty()) {
                throw new TrialFailure(loaded.blocked().isEmpty()
                        ? "路由后串行没有可用的子 Agent：请至少启用 1 个获允许的子 Agent"
                        : "路由后串行没有可用的子 Agent：" + loaded.blocked().size()
                                + " 位专家未获本编排允许（" + blockedReasons(loaded.blocked())
                                + "），请检查编排策略与对应 Agent 配置");
            }
            // 1) JUDGE：严格版判路由，失败即 FAIL，绝不拿第一条候选顶上。
            // 模型只决定选谁；真正的接力顺序用候选上的执行顺序，不用判定数组的先后
            List<RoutedIntent> judged = new ArrayList<>(chooseOrdered(parent, spec, parentLimits, runtime, userText,
                    candidates, userId, sessionId, deadlineNanos));
            judged.sort(Comparator
                    .comparingInt((RoutedIntent item) -> {
                        Integer order = item.candidate().route().getSortOrder();
                        return order == null ? Integer.MAX_VALUE : order;
                    })
                    .thenComparing(item -> item.candidate().route().getRouteKey(),
                            Comparator.nullsLast(String::compareTo)));
            // 2) 上限：串行不扇出，超出配置上限的按执行顺序截断并标「已跳过」
            int cap = runtime.maxRouteChildrenOrDefault();
            List<RoutedIntent> picked = judged.size() > cap
                    ? new ArrayList<>(judged.subList(0, cap))
                    : judged;
            List<RoutedIntent> intents = new ArrayList<>(picked.size());
            for (int i = 0; i < picked.size(); i++) {
                RoutedIntent base = picked.get(i);
                // 标题在派发前定稿：串行时时间线顺序＝执行顺序
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
            // 决策块只写「判定出几个意图、分给谁」的人话；机器可读 JSON 仍进 ROUTE span attrs
            String decisionJson = RouteDecisionParser.toDecisionJson(intents.stream()
                    .map(item -> new RouteDecisionParser.Item(
                            item.candidate().route().getRouteKey() == null ? ""
                                    : item.candidate().route().getRouteKey(),
                            item.subQuery()))
                    .toList());
            if (progress != null && intents.size() >= 2) {
                progress.route(RouterTexts.decisionLine(intents));
            }
            // 3) 串行 FANOUT：复用固定串行实现，前置摘要 / 部分失败继续 / P&E 冒泡一并继承
            // 多子或 VOTE 时同样屏蔽子正文：投票结论出来前不能让某一路先流出去
            boolean mute = intents.size() > 1
                    || runtime.answerModeEnum() == RouterAnswerMode.SUMMARIZE
                    || runtime.answerModeEnum() == RouterAnswerMode.VOTE;
            TrialProgress childProgress = mute ? muteAssistant(progress, true) : progress;
            List<ChildOutcome> outcomes = children.runFixedSequence(parent, modelUser, userText, userId, sessionId,
                    deadlineNanos, childProgress, trace, ChildRunner.childAgentParent(trace, route.id()), intents, runtime);
            // 所有执行步都没产出正文：整次 FAIL，不拿占位段假装成功
            if (!outcomes.isEmpty() && outcomes.stream().allMatch(outcome -> outcome.turn() == null)) {
                throw new TrialFailure("路由后串行全部子 Agent 均未完成，请检查子 Agent 配置或稍后重试");
            }
            List<ChildOutcome> all = new ArrayList<>(outcomes);
            for (RoutedIntent skip : skipped) {
                all.add(ChildOutcome.skipped(skip,
                        "「" + childLabel(skip.candidate().agent()) + "」超出串行上限 " + cap + "，未执行"));
            }
            closeRouterThenSequenceRoute(route, runtime, spec, all, decisionJson);
            List<String> notices = new ArrayList<>();
            List<RouteSegment> segments = new ArrayList<>();
            for (ChildOutcome outcome : all) {
                notices.addAll(outcome.notices());
                // 跳过不是失败：只留「已跳过」行，不写成子 Agent 失败
                segments.add(new RouteSegment(outcome.intent().routeLine(), outcome.turn(),
                        outcome.skipped() ? null : outcome.error(), outcome.children()));
            }
            /*
             * 未获允许的子不参与判路由，但必须在时间线留「已跳过」：
             * 只写 routeLine + notice，不塞进 all，避免改变终答口径。
             */
            for (ChildOutcome blocked : blockedOutcomes(loaded.blocked(), userText)) {
                notices.addAll(blocked.notices());
                segments.add(new RouteSegment(blocked.intent().routeLine(), null, null));
            }
            // 4) AGGREGATE：串行结束后再汇总，CONCAT / SUMMARIZE / VOTE 与现网同口径
            AggregateOutcome aggregate = answers.finishAnswer(parent, spec, parentLimits, runtime, userText, all, intents,
                    userId, sessionId, deadlineNanos, progress, trace, route.id());
            applyAggregate(segments, notices, aggregate);
            TrialTurn turn = aggregate.turn();
            List<String> lines = segments.stream().map(RouteSegment::routeLine).toList();
            return new RoutedTurn(String.join("；", lines), null, segments, notices, turn, null);
        } catch (RuntimeException ex) {
            log.warn("路由后串行执行失败 parentId={} type={} cause={}",
                    rootId(parent), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            route.error("路由后串行失败", ex);
            throw ex;
        } finally {
            RouteSpan.unbind(trace);
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
    private RoutedTurn executeCriticLoop(AgentDef parent, MaRunSpec spec, String userText, long userId,
                                         long sessionId, TrialProgress progress, AgentRuntimeConfig runtime,
                                         EffectiveLimits parentLimits) {
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, "critic-loop", rootId(parent));
        // 评审环的所有阶段都静音 assistant：初稿若直接流出，REVISE 后会变成「两版答复」拼在时间线上
        // 评审环不按参与者分块交结论：仍只在收尾推一版执行稿，避免和终稿叠成两段
        TrialProgress stageProgress = muteAssistant(progress, false);
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
            Candidates loaded = loadCandidates(rootId(parent), runtime, spec);
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
                    userId, sessionId, deadlineNanos, stageProgress, trace, route.id(), "executor", 0);
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
                            userId, sessionId, deadlineNanos, stageProgress, trace, route.id(), "critic", round);
                } catch (TrialFailure ex) {
                    // 墙钟/根取消属于整次失败，不做降级；其它评审失败采用当前稿
                    if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                        throw ex;
                    }
                    log.warn("评审环评审失败 parentId={} round={} type={} cause={}",
                            rootId(parent), round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    degradeNote = "评审未完成，已返回执行稿";
                    segments.add(new RouteSegment(reviewLine + " · 未完成", null, null));
                    break;
                } catch (RuntimeException ex) {
                    log.warn("评审环评审异常 parentId={} round={} type={} cause={}",
                            rootId(parent), round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
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
                            userId, sessionId, deadlineNanos, stageProgress, trace, route.id(), "executor", round);
                } catch (TrialFailure ex) {
                    if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                        throw ex;
                    }
                    log.warn("评审环回修失败 parentId={} round={} type={} cause={}",
                            rootId(parent), round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    degradeNote = "回修未完成，已返回上一版执行稿";
                    segments.add(new RouteSegment(reviseLine + " · 未完成", null, null));
                    break;
                } catch (RuntimeException ex) {
                    log.warn("评审环回修异常 parentId={} round={} type={} cause={}",
                            rootId(parent), round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
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
            TrialTurn turn = answers.mergeTurns(stages, finalText);
            if (progress != null) {
                progress.assistant(turn.getAssistantText());
            }
            closeCriticRoute(route, runtime, spec, roundsUsed, decision, parseFailed);
            /*
             * 未获允许/被挡下的候选：只追加「已跳过」路由行，不进 stages，
             * 因此不参与终答、不计 token，也不会触发额外 assistant 推送。
             */
            for (ChildOutcome blocked : blockedOutcomes(loaded.blocked(), userText)) {
                notices.addAll(blocked.notices());
                segments.add(new RouteSegment(blocked.intent().routeLine(), null, null));
            }
            String summary = "评审环：" + roundsUsed + " 轮 · " + criticDecisionText(decision);
            return new RoutedTurn(summary, null, segments, notices, turn, null);
        } catch (RuntimeException ex) {
            log.warn("评审环执行失败 parentId={} type={} cause={}",
                    rootId(parent), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            route.error("评审环失败", ex);
            throw ex;
        } finally {
            RouteSpan.unbind(trace);
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
        return children.runOneChild(parent, intent, prompt, subQuery, userId, sessionId, deadlineNanos, progress, trace,
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
    private void closeCriticRoute(RouteSpan route, AgentRuntimeConfig runtime, MaRunSpec spec, int roundsUsed,
                                  String decision, boolean parseFailed) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ma.template", runtime.maTemplateOrDefault());
        // 评审环没有判路由 LLM：显式打点，泳道据此展示「无判路由」而不是假 JUDGE
        fields.put("ma.skip_judge", "true");
        // 绑根没有合成根步骤：方案标识补在这里，按 ma.def_id 检索时不会漏
        fields.putAll(RouterObs.maAttrs(spec));
        fields.put("ma.critic_rounds_used", roundsUsed);
        fields.put("ma.critic_decision", decision);
        if (parseFailed) {
            fields.put("ma.critic_parse", "fail");
        }
        // 评审环若选了共享草稿：执行者与评审者共享同一块板，统计只写次数不写正文
        fields.putAll(RouterObs.scratchAttrs());
        // 评审环不写 route_decision：模板本身不判路由
        route.ok("评审环完成：" + criticDecisionText(decision) + "，评审 " + roundsUsed + " 轮", null, null,
                "评审环×" + roundsUsed + " 轮 · " + criticDecisionText(decision), fields);
    }

    /**
     * 关闭固定模板的根 ROUTE：写实际模板 / 汇总策略 / 上下文策略，并附跳过与失败计数。
     */
    private void closeFixedRoute(RouteSpan route, AgentRuntimeConfig runtime, MaRunSpec spec, String template,
                                 List<ChildOutcome> outcomes, String decisionJson, boolean parallel) {
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
        // 绑根没有合成根步骤：方案标识补在这里，按 ma.def_id 检索时不会漏
        fields.putAll(RouterObs.maAttrs(spec));
        // 共享草稿只写键数 / 冲突次数（有则显示），不写任何 key / value 正文
        fields.putAll(RouterObs.scratchAttrs());
        route.ok((parallel ? "固定并行完成 " : "固定串行完成 ") + ok + " 子", decisionJson, null,
                brief.toString(), fields);
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
     * <p>序号即执行顺序：路由后串行按候选上的执行顺序接力，不按判定数组的先后。
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
        line.append(priorSummary ? "（后步会看到前面专家的成文结论）" : "（上下文隔离，后步看不到前置正文）");
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
    private void closeRouterThenSequenceRoute(RouteSpan route, AgentRuntimeConfig runtime, MaRunSpec spec,
                                              List<ChildOutcome> outcomes, String decisionJson) {
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
        // 绑根没有合成根步骤：方案标识补在这里，按 ma.def_id 检索时不会漏
        attrs.putAll(RouterObs.maAttrs(spec));
        // 共享草稿只写键数 / 冲突次数（有则显示），不写任何 key / value 正文
        attrs.putAll(RouterObs.scratchAttrs());
        route.ok("路由后串行完成 " + ok + " 子", decisionJson, null, brief.toString(), attrs);
    }

    /**
     * 把终答汇总结论挂到分段列表：赢家子段打「★ 选中」，再追加投票结论行。
     *
     * @param segments  已有子分段（顺序与 outcomes 一致）
     * @param notices   SYSTEM 说明收集器
     * @param aggregate 汇总结果
     */
    private void applyAggregate(List<RouteSegment> segments, List<String> notices, AggregateOutcome aggregate) {
        notices.addAll(aggregate.notices());
        int winner = aggregate.winnerIndex();
        // 赢家子段补 ★：与 COMPETE 同一口径，时间线能看出终答取自哪一位
        if (winner >= 0 && winner < segments.size()) {
            RouteSegment old = segments.get(winner);
            segments.set(winner, new RouteSegment(
                    (old.routeLine() == null ? "" : old.routeLine()) + " · ★ 选中", old.turn(), old.error(),
                    old.children()));
        }
        if (aggregate.segmentLine() != null) {
            segments.add(new RouteSegment(aggregate.segmentLine(), null, null));
        }
    }

    /**
     * 并行 / 多子执行时丢掉 assistant 增量，避免多子正文交错；route/tool/reasoning 仍可见。
     *
     * @param progress           下游进度；可空
     * @param deliverConclusions true 时在参与者结束补推整段成文结论（按路由行钉住）；
     *                           评审环传 false，避免和收尾终稿叠成两段
     */
    private TrialProgress muteAssistant(TrialProgress progress, boolean deliverConclusions) {
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
                // token 增量静音：结论改由 deliverParticipantConclusion 按参与者整段交
            }

            /**
             * 参与者结束时的整段结论。先钉路由行，正文才能归到这个名字下，而不是并进上一位。
             */
            @Override
            public void deliverParticipantConclusion(String routeLine, String text) {
                if (!deliverConclusions || text == null || text.isBlank()) {
                    return;
                }
                if (routeLine != null && !routeLine.isBlank()) {
                    progress.route(routeLine);
                }
                progress.assistant(text);
            }

            /**
             * 内层方案要能写出子结论和汇总结论，不能再套一层外层静音。
             */
            @Override
            public TrialProgress conclusionChannel() {
                return progress.conclusionChannel();
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
     * 判路由主链（并行模板）。所属域：runtime。
     *
     * <p>刀3 起多一个可选的 {@link RouteHint}：
     * <ol>
     *   <li><b>强制线路</b>：用户手动换线后的一轮，跳过关键字与判路由，直接命中该候选；</li>
     *   <li><b>沿用线路</b>：把「本会话当前线路是 X，仍属同一业务请继续输出 X」拼进判路由提示词，
     *       判路由仍可返回别的键（＝Router 改派）；</li>
     *   <li>{@code hint == null} 时三条路径与刀3 之前完全一致，调试 / 试跑不受影响。</li>
     * </ol>
     *
     * <p>强制目标若已被停用 / 删除（候选不再出现），不伪造命中：回落正常判路由，
     * 由上层写一条「手动换线目标已失效」的可见说明。
     *
     * @param hint 线路提示；可空
     * @return 命中意图 + 是否走了手动换线 + 强制目标是否失效
     */
    private RoutedChoice choose(AgentDef parent, MaRunSpec spec, EffectiveLimits parentLimits,
                                AgentRuntimeConfig runtime, String userText, List<Candidate> candidates,
                                long userId, long sessionId, long deadlineNanos, RouteHint hint) {
        int maxN = runtime.maxRouteChildrenOrDefault();
        boolean forceMissed = false;
        // 1) 手动换线优先于一切：用户已经指定线路，不再花一次判路由，也不再被关键字抢先
        if (hint != null && hint.forced()) {
            Candidate forced = byRouteKey(candidates, hint.forceRouteKey());
            if (forced != null) {
                // 强制轮恒为单意图：用户选的是一个候选，不是协作。
                // 理由只作为「来源标记」传给前端识别手动换线，前端不会把它当判定依据展示
                return new RoutedChoice(List.of(new RoutedIntent(forced, userText, "手动换线")), true, false);
            }
            forceMissed = true;
        }
        // 入口点名方案优先于关键字和模型：裸专家的 route_key 即使撞上问句里的词，也不能把已点名的方案截走
        RoutedChoice named = entryNamedChoice(spec, userText, candidates, forceMissed);
        if (named != null) {
            return named;
        }
        List<AgentRoute> keywordHits = RouteKeywordPicker.matchAll(userText,
                candidates.stream().map(Candidate::route).toList(),
                runtime.multiIntentOn() ? maxN : 1);
        if (!keywordHits.isEmpty()) {
            List<RoutedIntent> intents = new ArrayList<>();
            for (AgentRoute route : keywordHits) {
                Candidate hit = candidateOf(candidates, route);
                // 关键字无法可靠切句，整句作 sub_query；理由只作「来源标记」，前端识别后不会当依据展示
                intents.add(new RoutedIntent(hit, userText, "关键字"));
            }
            RoutedChoice covered = entryCoveringChoice(spec, userText, candidates, forceMissed);
            if (covered != null) {
                return covered;
            }
            return new RoutedChoice(intents, false, forceMissed);
        }
        Optional<Long> parentModel = resolveRouterModel(parent, spec);
        if (parentModel.isEmpty()) {
            Candidate first = candidates.get(0);
            // 方案以自己为根：只能靠方案配判路由模型；没配就退化为第一条，文案必须点名是方案缺配置
            String reason = spec != null
                    ? "方案未配置判路由模型（maJudgeModelInstanceId / maJudgeModel），未命中关键词，使用第一条候选"
                    : "未命中关键词且没有可用的判路由模型，使用第一条候选";
            return new RoutedChoice(List.of(new RoutedIntent(first, userText, reason)), false, forceMissed);
        }
        try {
            assertParentDeadline(deadlineNanos);
            long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            EffectiveLimits judgeLimits = parentLimits.withTimeoutMs(Math.min(parentLimits.timeoutMs(), remainingMs));
            String reply = react.judge(
                    MeterScope.console(rootId(parent), parentModel.get(), sessionId, userId),
                    judgePrompt(parent, candidates, runtime, userText, hint, spec), userText, judgeLimits);
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
                        // 理由随本次判定产出；为空就留空，由前端降级为中性句，不再补系统标签
                        intents.add(new RoutedIntent(hit, item.subQuery(), item.reason()));
                    }
                }
                if (!intents.isEmpty()) {
                    RoutedChoice covered = entryCoveringChoice(spec, userText, candidates, forceMissed);
                    if (covered != null) {
                        return covered;
                    }
                    return new RoutedChoice(intents, false, forceMissed);
                }
            }
        } catch (TrialFailure ex) {
            if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                throw ex;
            }
            Candidate first = candidates.get(0);
            return new RoutedChoice(List.of(new RoutedIntent(first, userText, "路由模型判定失败，使用第一条")),
                    false, forceMissed);
        }
        Candidate first = candidates.get(0);
        return new RoutedChoice(List.of(new RoutedIntent(first, userText, "路由模型未给出有效 route_key，使用第一条")),
                false, forceMissed);
    }

    /**
     * 入口问句点名了某个已启用方案时，整句只交给该方案。
     *
     * <p>非入口返回 null，普通方案内部的选人不受影响。手动换线已在调用前返回。
     *
     * @param spec        当前方案；入口列是 {@code ENTRY_ROUTER}
     * @param userText    用户原问
     * @param candidates  本轮可用候选
     * @param forceMissed 强制线路已失效，需要带给上层写说明
     * @return 只含该方案的选择；没有点名时 null
     */
    private RoutedChoice entryNamedChoice(MaRunSpec spec, String userText, List<Candidate> candidates,
                                          boolean forceMissed) {
        if (!isEntryRouter(spec)) {
            return null;
        }
        Candidate named = EntrySchemePreference.namedScheme(userText, candidates);
        if (named == null) {
            return null;
        }
        return new RoutedChoice(List.of(new RoutedIntent(named, userText, EntrySchemePreference.NAMED_REASON)),
                false, forceMissed);
    }

    /**
     * 入口问句打中同一方案的多项交付时，丢掉单兵选择，整句交给该方案再分发。
     *
     * <p>单项问题打不中两名成员，这里返回 null，原来的专家选择保留。
     * 成员名称和简介按已装载的子候选现查，不改库里的池和简介。
     *
     * @param spec        当前方案
     * @param userText    用户原问
     * @param candidates  本轮可用候选
     * @param forceMissed 强制线路已失效
     * @return 只含该方案的选择；没有多项覆盖时 null
     */
    private RoutedChoice entryCoveringChoice(MaRunSpec spec, String userText, List<Candidate> candidates,
                                             boolean forceMissed) {
        if (!isEntryRouter(spec)) {
            return null;
        }
        Candidate covered = EntrySchemePreference.coveringScheme(userText, candidates, entryMemberDuties(candidates));
        if (covered == null) {
            return null;
        }
        return new RoutedChoice(List.of(new RoutedIntent(covered, userText, EntrySchemePreference.MULTI_REASON)),
                false, forceMissed);
    }

    /**
     * 入口池里每个方案的启用成员（名称 + 简介），供多项交付判定。
     *
     * <p>只读已启用的单兵成员。方案套方案的下一层不在这里展开，避免把正常的多层分发误判成覆盖。
     *
     * @param candidates 入口本轮可用候选
     * @return 方案主键 → 成员职责；没有方案候选时为空表
     */
    private Map<Long, List<EntrySchemePreference.MemberDuty>> entryMemberDuties(List<Candidate> candidates) {
        Map<Long, List<Long>> memberIds = new LinkedHashMap<>();
        List<Long> allIds = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!candidate.isMaTarget() || candidate.maTarget() == null || candidate.maTarget().maId() == null) {
                continue;
            }
            List<Long> ids = new ArrayList<>();
            for (MaCandidate member : candidate.maTarget().candidates()) {
                if (member.getAgentId() == null || member.isMaTarget() || member.isWorkflowTarget()) {
                    continue;
                }
                if (member.getEnabled() != null && member.getEnabled() != AgentStatus.ENABLED) {
                    continue;
                }
                ids.add(member.getAgentId());
                allIds.add(member.getAgentId());
            }
            memberIds.put(candidate.maTarget().maId(), ids);
        }
        Map<Long, AgentDef> defs = new LinkedHashMap<>();
        if (!allIds.isEmpty()) {
            for (AgentDef agent : agents.findByIds(allIds)) {
                if (agent != null && agent.getId() != null) {
                    defs.put(agent.getId(), agent);
                }
            }
        }
        Map<Long, List<EntrySchemePreference.MemberDuty>> duties = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Long>> entry : memberIds.entrySet()) {
            List<EntrySchemePreference.MemberDuty> rows = new ArrayList<>();
            for (Long id : entry.getValue()) {
                AgentDef agent = defs.get(id);
                if (agent == null) {
                    continue;
                }
                rows.add(new EntrySchemePreference.MemberDuty(agent.getName(), agent.getDescription()));
            }
            duties.put(entry.getKey(), rows);
        }
        return duties;
    }

    /** 当前执行来源是不是工作台入口。列上的模板码为准，不看配置 JSON 里残留的串行码。 */
    private static boolean isEntryRouter(MaRunSpec spec) {
        return spec != null && AgentRuntimeConfig.isEntryRouterTemplate(spec.template());
    }

    /**
     * 按 route_key 找候选（大小写不敏感）；找不到返回 null。
     *
     * <p>手动换线与沿用线路都要按「方案里的键」定位候选，不允许调用方自己拼匹配逻辑，
     * 否则大小写 / 首尾空格的差异会让同一个键一处命中、一处落空。
     *
     * @param candidates 可用候选
     * @param routeKey   目标键；可空
     * @return 命中的候选；无匹配时为空
     */
    private static Candidate byRouteKey(List<Candidate> candidates, String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return null;
        }
        String wanted = routeKey.trim().toLowerCase();
        for (Candidate candidate : candidates) {
            String key = candidate.route().getRouteKey();
            if (key != null && key.trim().toLowerCase().equals(wanted)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 线路类可见说明：手动换线 / Router 改派 / 强制目标失效。
     *
     * <p>只写用户能理解的中文与候选展示名，不暴露 route_key；
     * 这些说明会落成 SYSTEM 事件（思考区），不打断正文。
     *
     * @param choice     判路由结果
     * @param hint       本轮线路提示；可空
     * @param candidates 可用候选（用于把旧键翻译成展示名）
     * @param intents    最终执行的意图（已被多意图开关截断）
     * @return 中文说明；不需要说明时为空列表
     */
    private List<String> routeNotices(RoutedChoice choice, RouteHint hint, List<Candidate> candidates,
                                      List<RoutedIntent> intents) {
        List<String> notices = new ArrayList<>();
        if (choice.forceMissed()) {
            notices.add("手动换线的目标线路已停用或不存在，本轮改由 Router 重新判定");
        }
        if (intents.isEmpty()) {
            return notices;
        }
        String chosen = intents.get(0).candidate().route().getRouteKey();
        if (choice.manual()) {
            notices.add("手动换线：本轮按你选择的线路「" + childLabel(intents.get(0).candidate().agent())
                    + "」执行，未走判路由");
            return notices;
        }
        // 改派只对单意图有意义：多意图没有「一条主线路」，本轮会清空粘性
        if (hint == null || hint.forceRouteKey() != null || intents.size() >= 2) {
            return notices;
        }
        String stickyKey = hint.stickyRouteKey();
        if (stickyKey == null || chosen == null || stickyKey.trim().equalsIgnoreCase(chosen.trim())) {
            return notices;
        }
        Candidate before = byRouteKey(candidates, stickyKey);
        String from = before == null ? "原线路" : childLabel(before.agent());
        notices.add("已将线路改派：" + from + " → " + childLabel(intents.get(0).candidate().agent()));
        return notices;
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
    private List<RoutedIntent> chooseOrdered(AgentDef parent, MaRunSpec spec, EffectiveLimits parentLimits,
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
                intents.add(new RoutedIntent(candidateOf(candidates, route), userText, "关键字"));
            }
            return intents;
        }
        Optional<Long> parentModel = resolveRouterModel(parent, spec);
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
                    MeterScope.console(rootId(parent), parentModel.get(), sessionId, userId),
                    judgePrompt(parent, candidates, runtime, userText, null, spec), userText, judgeLimits);
        } catch (TrialFailure ex) {
            // 墙钟属于整次失败，保持 finishReason 供上层识别；其它判路由错误统一加中文前缀
            if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                throw ex;
            }
            throw new TrialFailure("判路由失败：" + ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("路由后串行判路由异常 parentId={} type={} cause={}",
                    rootId(parent), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
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
                // 与并行模板同口径：理由随本次判定产出，不在这里补系统标签
                intents.add(new RoutedIntent(hit, item.subQuery(), item.reason()));
            }
        }
        if (intents.isEmpty()) {
            throw new TrialFailure("判路由失败：模型给出的 route_key 均未匹配当前候选，请检查候选配置");
        }
        return intents;
    }

    /**
     * 撞到父墙钟即整次 FAIL（不降级）。实现同 {@link RouterClock#assertBefore(long)}，
     * 保留本方法是为了让六套模板的调用点读起来仍是「本模板的墙钟检查」。
     *
     * @param deadlineNanos 父墙钟截止
     */
    private void assertParentDeadline(long deadlineNanos) {
        RouterClock.assertBefore(deadlineNanos);
    }

    /**
     * 路由展示摘要。无 route_key 时不拼空括号；子 Agent 优先名称，否则编码。
     *
     * <p>只做转发：文案口径单一真源在 {@link RouterTexts#routeSummary}，
     * 理由为空时不拼「依据：」，由前端降级成中性句。
     *
     * @param hit    命中的候选
     * @param reason 判路由模型给出的依据；可空
     * @return 「路由到 …」中文摘要
     */
    private String routeSummary(Candidate hit, String reason) {
        return RouterTexts.routeSummary(hit, reason);
    }

    /**
     * 子 Agent 显示名：名称 → 编码 → 兜底文案。实现见 {@link RouterTexts#childLabel}.
     *
     * @param agent 子 Agent；可空
     * @return 非空展示名
     */
    private String childLabel(AgentDef agent) {
        return RouterTexts.childLabel(agent);
    }

    /**
     * 判路由提示词（内置组装）。所属域：runtime。
     *
     * <p>结构固定为三段，页面 / 方案都不再参与组装：
     * <ol>
     *   <li>网关调度指令：只派活、不答题；</li>
     *   <li>【可用专家】：本方案启用候选的 route_key｜展示名｜Agent 简介（简介单一真源在 Agent）；</li>
     *   <li>【调度规则】：只输出候选内的键；寒暄 / 问能力 / 无匹配 / 不当内容派给聊天机器人；
     *       禁止派给名单外或另一个 Router。</li>
     * </ol>
     *
     * <p>{@code hint} 有沿用线路时追加一段「本会话当前线路是 X」的说明：
     * 只建议优先沿用，不剥夺判路由改派的能力——用户明显换了业务仍应输出新键。
     * 提示词注入失败或模型不听话都以判路由结果为准，不阻塞主路径。
     *
     * @param parent           编排根（取用途模板前缀）
     * @param candidates       可用候选
     * @param runtime          运行配置（多意图开关 / 上限）
     * @param userText         用户原问（模板占位符）
     * @param hint             线路提示；可空
     * @param spec             方案执行来源；可空（旧口径没有方案）
     * @return 完整提示词
     */
    private String judgePrompt(AgentDef parent, List<Candidate> candidates, AgentRuntimeConfig runtime,
                               String userText, RouteHint hint, MaRunSpec spec) {
        StringBuilder prompt = new StringBuilder();
        // ROUTER 用途模板可能自带 {{user_query}}；传进去让模板侧占位符有值，避免严格模式失败。
        // 有方案时走 assembleRouter：模板引用与追加要求来自默认接待页（ma_def.config_json），
        // 与专家人设的 agent_def 列分开，改 Router 不会动到需求拆解等专家。
        String assembled;
        if (spec != null) {
            SystemPromptAssembler.Assembled routerPrompt =
                    prompts.assembleRouter(rootName(parent, spec), runtime, userText);
            assembled = routerPrompt == null ? null : routerPrompt.text();
        } else {
            assembled = prompts.build(parent, userText);
        }
        if (assembled != null && !assembled.isBlank()) {
            prompt.append(assembled).append("\n\n");
        }
        // 网关调度指令：平台内置文案，用户与方案都不能改，避免「一套方案一套口径」
        prompt.append("你是平台的调度网关：只负责把用户这一轮请求派给【可用专家】里最合适的一位，"
                + "不直接回答用户问题，也不替专家作答。\n");
        // 输出契约与 RouteDecisionParser 同口径：多意图开关只影响条数上限，不影响解析格式。
        // reason 必须随同一次判定产出：工作台智能网关步骤直接展示它，
        // 不允许事后再打一轮模型「解释一下为什么」，也不允许模板泄露内部键。
        String reasonRule = "每项都必须带一句中文 reason：说明为什么选这位（引用该专家简介里的职责，"
                + "或指出用户这句请求的关键特征），只写一句、不超过 40 字；"
                + "禁止出现 route_key、模板名、父模型、子模型、作为 AI 模型、根据系统设定等内部说法。";
        int maxN = runtime.maxRouteChildrenOrDefault();
        if (runtime.multiIntentOn()) {
            prompt.append("本次做意图路由：用户可能含多个意图。输出 JSON 数组，每项 ")
                    .append("{\"route_key\":\"...\",\"sub_query\":\"切给该子的子问题\",\"reason\":\"一句话依据\"}。")
                    .append(reasonRule)
                    .append("按重要性排序；同 route_key 只出现一次；最多 ").append(maxN).append(" 项。")
                    .append("单意图则数组长度为 1。不要解释。都不合适时回复 []。\n");
        } else {
            prompt.append("本次只做路由选择：输出 JSON 数组且仅一项 ")
                    .append("{\"route_key\":\"...\",\"sub_query\":\"...\",\"reason\":\"一句话依据\"}，"
                            + "或只回复一个 route_key。")
                    .append(reasonRule)
                    .append("不要解释。都不合适时回复 [] 或 NONE。\n");
        }
        // 沿用线路说明放在候选列表之前：先给定上下文，再看候选，模型更容易「先认同再选择」
        if (hint != null && hint.stickyRouteKey() != null) {
            Candidate sticky = byRouteKey(candidates, hint.stickyRouteKey());
            prompt.append("本会话当前线路是 ").append(hint.stickyRouteKey().trim());
            if (sticky != null) {
                prompt.append("（候选名 ").append(childLabel(sticky.agent())).append("）");
            }
            prompt.append("；若本轮仍属同一业务，请继续输出 ").append(hint.stickyRouteKey().trim())
                    .append("；明显切换业务时再输出新的 route_key。\n");
        }
        // 动态专家列表：职责简介只读单一真源（Agent 用 agent_def.description，
        // MA 候选用目标方案的 ma_def.description），不再读 ma_candidate.intent_desc
        prompt.append("\n【可用专家】（route_key｜专家｜职责简介）\n");
        for (Candidate candidate : candidates) {
            String key = candidate.route().getRouteKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            prompt.append("- ").append(key.trim()).append('｜').append(childLabel(candidate.agent()));
            String brief = RouterTexts.descriptionOf(candidate.agent());
            if (brief != null) {
                prompt.append('｜').append(brief);
            }
            // 入口才标出方案 / 专家，避免模型把方案当成又一个单兵
            if (isEntryRouter(spec)) {
                prompt.append(candidate.isMaTarget() ? "｜方案"
                        : candidate.isWorkflowTarget() ? "｜工作流" : "｜专家");
            }
            prompt.append('\n');
        }
        // 调度规则：兜底目标固定为第一位候选；默认接待里它是聊天机器人（只认 Agent，MA 目标不接管兜底）
        Candidate fallback = candidates.get(0);
        boolean receptionLabel = false;
        if (spec != null && spec.receptionDefault()) {
            // 聊天机器人已被挡下（停用 / 未授权）时不要把它写进兜底文案，否则模型会输出一个不存在的键
            Candidate assistant = spec.firstAgentCandidate()
                    .map(item -> byRouteKey(candidates, item.getRouteKey()))
                    .orElse(null);
            if (assistant != null) {
                fallback = assistant;
                receptionLabel = true;
            }
        }
        String fallbackLabel = fallbackLabel(fallback, receptionLabel);
        prompt.append("\n【调度规则】\n")
                .append("1) route_key 只能取自上面的【可用专家】，不得编造，也不得指向名单外的 Agent、"
                        + "另一个 Router、方案或你自己。\n")
                .append("2) 用户只是寒暄、致谢、询问你能做什么、需求还说不清，或者没有合适的专家时，"
                        + "派给 ").append(fallbackLabel).append("。\n")
                .append("3) 用户明显换到另一类业务时，改派给对应专家；始终只输出 route_key 与子问题，不输出解释。\n");
        if (isEntryRouter(spec)) {
            prompt.append("4) 用户点名了名单中某个方案的名称时，只派给该方案，不要改派给它的成员或职责相近的单个专家。\n")
                    .append("5) 用户这一句同时包含多项交付，且某个方案的简介或成员能覆盖这些交付时，只派给该方案，"
                            + "由方案再分发；只涉及一项时可以直接派给对应专家。闲聊仍按第 2 条。\n");
        }
        return prompt.toString();
    }

    /**
     * 兜底专家的文案：默认接待明确叫「聊天机器人」，其它方案按「第一位候选」表述。
     *
     * @param fallback         第一位候选
     * @param receptionDefault 是否平台默认接待
     * @return 形如「reception_assistant（聊天机器人）」
     */
    private String fallbackLabel(Candidate fallback, boolean receptionDefault) {
        String key = fallback.route().getRouteKey() == null ? "" : fallback.route().getRouteKey().trim();
        String name = childLabel(fallback.agent());
        String label;
        if (receptionDefault) {
            // 首位候选就是聊天机器人；名字本身已是「聊天机器人」时不再重复
            label = "聊天机器人".equals(name) ? "聊天机器人" : "聊天机器人「" + name + "」";
        } else {
            label = name + "，首位候选";
        }
        return key.isEmpty() ? label : key + "（" + label + "）";
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
        return loadCandidates(parentId, runtime, null);
    }

    /**
     * 候选装载（方案优先）。
     *
     * <p>读源纪律：{@code spec} 非空时只用 {@code ma_candidate}，<b>不再</b>读 {@code agent_route}——
     * 这正是「改方案候选不经旧编排 Tab 也能影响下一轮」的落点。{@code spec} 为空才是旧口径。
     * 过滤顺序与旧实现一致：子未删且启用 → 子 run_mode 合法 → 本编排允许该子模式 → 子允许被编排。
     *
     * @param parentId 编排根主键；虚拟根为空，仅旧口径会用到
     * @param runtime  编排根运行配置（读 maChildRunModesAllowed）
     * @param spec     方案执行来源；可空
     * @return usable=可执行候选；blocked=被开关/子模式挡下的候选
     */
    private Candidates loadCandidates(Long parentId, AgentRuntimeConfig runtime, MaRunSpec spec) {
        // 方案链路走专表：候选可能是子 Agent，也可能是另一套方案（刀5）
        if (spec != null) {
            return loadSpecCandidates(spec, runtime);
        }
        List<Candidate> usable = new ArrayList<>();
        List<BlockedCandidate> blocked = new ArrayList<>();
        List<AgentRoute> routeRows = routes.listByParentId(parentId);
        for (AgentRoute route : routeRows) {
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
     * 方案候选装载（含 MA / 工作流目标）。所属域：runtime。
     *
     * <p>读源纪律：只认 {@code ma_candidate}，已按启用过滤、按 sort_no 排好序，不再重排。
     * 三类候选的门闩不同：
     * <ul>
     *   <li>AGENT：子未删且启用 → run_mode 合法 → 本编排允许该子模式 → 允许被编排（与旧口径一致）；</li>
     *   <li>MA：只要求目标方案的执行来源已装载（装载时已校验启用 / 同租户 / 非默认接待 / 只一层）。
     *       目标方案若在装载后被停用或删除，本轮按「已跳过」处理，不静默换成别的候选。</li>
     *   <li>WORKFLOW（刀5B）：只要求目标工作流的执行来源已装载（装载时已校验同租户 / 已发布 /
     *       有默认发布指针 / 说明非空）。取消发布或移出池后同样按「已跳过」处理。</li>
     * </ul>
     *
     * @param spec    方案执行来源（非空）
     * @param runtime 编排根运行配置（读 maChildRunModesAllowed）
     * @return usable=可执行候选；blocked=被门闩挡下的候选
     */
    private Candidates loadSpecCandidates(MaRunSpec spec, AgentRuntimeConfig runtime) {
        List<Candidate> usable = new ArrayList<>();
        List<BlockedCandidate> blocked = new ArrayList<>();
        for (MaCandidate item : spec.candidates()) {
            if (item.getEnabled() == null || item.getEnabled() != AgentStatus.ENABLED) {
                continue;
            }
            AgentRoute route = routeOf(item);
            // MA 目标：装载时就定好执行来源，缺失只能跳过，不能再下探第二层
            if (item.isMaTarget()) {
                AgentDef shell = maShell(spec, item);
                Candidate candidate = new Candidate(route, shell, spec.maTarget(item.getMaDefId()).orElse(null));
                // 装载时没把超限的下一跳放进目标表；这里给出熔断文案，不要写成「已停用」
                if (MaNestedContext.depth() >= AgentRuntimeConfig.MAX_MA_NEST_DEPTH) {
                    blocked.add(new BlockedCandidate(candidate, MaCandidate.TARGET_MA,
                            AgentRuntimeConfig.NEST_DEPTH_REJECTED));
                    continue;
                }
                if (candidate.maTarget() == null) {
                    blocked.add(new BlockedCandidate(candidate, MaCandidate.TARGET_MA,
                            "目标编排方案「" + childLabel(shell) + "」已停用、已删除或不允许作为路由目标"));
                } else {
                    usable.add(candidate);
                }
                continue;
            }
            // 工作流目标（刀5B）：只认装载成功的目标；定义被取消发布 / 移出池都按「已跳过」处理
            if (item.isWorkflowTarget()) {
                WorkflowRunSpec target = spec.workflowTarget(item.getWorkflowDefId()).orElse(null);
                AgentDef shell = workflowShell(item, target);
                Candidate candidate = new Candidate(route, shell, null, target);
                if (target == null) {
                    blocked.add(new BlockedCandidate(candidate, MaCandidate.TARGET_WORKFLOW,
                            "目标工作流「" + childLabel(shell) + "」未发布、已删除或已被移出路由池"));
                } else {
                    usable.add(candidate);
                }
                continue;
            }
            Optional<AgentDef> found = agents.findById(item.getAgentId())
                    .filter(agent -> agent.getStatus() != null && agent.getStatus() == AgentStatus.ENABLED)
                    .filter(agent -> AgentRuntimeConfig.validChildRunMode(agent.getRunMode()));
            if (found.isEmpty()) {
                continue;
            }
            Candidate candidate = new Candidate(route, found.get());
            String runMode = found.get().getRunMode();
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
     * MA 候选的只读外壳：让判路由提示词、时间线标题与汇总都按 Agent 口径复用同一套文案工具。
     *
     * <p>外壳主键为空、不落库：执行分支按 {@link Candidate#isMaTarget()} 直接委托给目标方案，
     * 任何按 agentId 回查 Agent 的地方都不会走到这里。
     *
     * @param parent 当前方案（拼兜底文案用）
     * @param item   MA 候选行
     * @return 展示用外壳（名称 / 编码 / 说明 / ROUTER 模式）
     */
    private AgentDef maShell(MaRunSpec parent, MaCandidate item) {
        MaRunSpec target = parent.maTarget(item.getMaDefId()).orElse(null);
        AgentDef shell = new AgentDef();
        shell.setRunMode(AgentRunMode.ROUTER.name());
        shell.setStatus(AgentStatus.ENABLED);
        if (target != null) {
            shell.setName(target.name());
            shell.setCode(target.code());
            shell.setDescription(target.description());
        }
        if (shell.getName() == null || shell.getName().isBlank()) {
            shell.setName(item.getRouteKey() == null ? "编排方案" : item.getRouteKey().trim());
        }
        return shell;
    }

    /**
     * 工作流候选的只读外壳（刀5B）：让判路由提示词、时间线标题与汇总按同一套 Agent 口径复用。
     *
     * <p>外壳主键为空、不落库；{@code runMode} 填 ROUTER 只是为了与方案外壳保持一致的展示口径，
     * 执行分支在读取运行模式之前就按 {@link Candidate#isWorkflowTarget()} 委托出去。
     * 职责简介取 {@code workflow_def.description}（意图说明唯一真源）。
     *
     * @param item   工作流候选行
     * @param target 目标工作流快照；未装载时为空（外壳仍要能显示名称，用于「已跳过」文案）
     * @return 展示用外壳
     */
    private AgentDef workflowShell(MaCandidate item, WorkflowRunSpec target) {
        AgentDef shell = new AgentDef();
        shell.setRunMode(AgentRunMode.ROUTER.name());
        shell.setStatus(AgentStatus.ENABLED);
        if (target != null) {
            shell.setName(target.name());
            shell.setCode(target.code());
            shell.setDescription(target.description());
        }
        if (shell.getName() == null || shell.getName().isBlank()) {
            shell.setName(item.getRouteKey() == null ? "工作流" : item.getRouteKey().trim());
        }
        return shell;
    }

    /**
     * 方案候选 → 执行器内部认的路由行（单条）。
     *
     * <p>只借 {@link AgentRoute} 的展示字段（route_key / sort_order）：
     * {@code parentAgentId} 填方案 id 只是为了日志可读，执行器不会再用它查路由。
     * MA 候选的 {@code childAgentId} 恒为 null（没有子 Agent），按 route_key 定位。
     *
     * @param item 方案候选
     * @return 路由行
     */
    private static AgentRoute routeOf(MaCandidate item) {
        AgentRoute route = new AgentRoute();
        route.setId(item.getId());
        route.setParentAgentId(item.getMaId());
        route.setChildAgentId(item.getAgentId());
        route.setRouteKey(item.getRouteKey());
        route.setSortOrder(item.getSortNo());
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    /**
     * 判路由 / 汇总模型解析：方案优先，旧 Agent 试跑才回落 Agent 模型槽。
     * 实现见 {@link AnswerAggregator#resolveRouterModel}.
     *
     * @param parent 旧 Agent 试跑的 ROUTER；方案链路为 null
     * @param spec   方案执行来源；可空
     * @return 模型实例 id；都不可用时为空
     */
    private Optional<Long> resolveRouterModel(AgentDef parent, MaRunSpec spec) {
        return answers.resolveRouterModel(parent, spec);
    }

    /**
     * 编排根主键：方案试跑没有 Agent 根，返回 null；旧 Agent 试跑返回 Agent 主键。
     *
     * <p>用于日志 / 计量归属 / 路由行查询；方案链路不依赖它（候选与模型都来自 spec）。
     *
     * @param parent 旧 Agent 试跑的 ROUTER；可为 null
     * @return Agent 主键或 null
     */
    private static Long rootId(AgentDef parent) {
        return parent == null ? null : parent.getId();
    }

    /**
     * 编排根展示名：方案名优先（方案就是根），旧 Agent 路径用 Agent 名。
     *
     * @param parent 旧 Agent 试跑的 ROUTER；可为 null
     * @param spec   方案来源；可空
     * @return 展示名；都为空时 null
     */
    private static String rootName(AgentDef parent, MaRunSpec spec) {
        if (spec != null) {
            return RouterObs.maRootName(spec);
        }
        return parent == null ? null : parent.getName();
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
     * 竞争选优候选不足的中文失败文案。
     *
     * <p>把「可用数 / 被挡数 / 原因」一次说清：用户才知道是去加候选、启用候选，还是开 allow_orchestrated。
     *
     * @param usable  当前可用候选数
     * @param blocked 被挡下的候选
     * @return 中文文案
     */
    private static String competeTooFewText(int usable, List<BlockedCandidate> blocked) {
        StringBuilder text = new StringBuilder("竞争选优至少需要 ")
                .append(AgentRuntimeConfig.MIN_COMPETE_CANDIDATES)
                .append(" 个可用候选，当前只有 ").append(usable).append(" 个");
        if (blocked != null && !blocked.isEmpty()) {
            text.append("（另有 ").append(blocked.size()).append(" 位专家未获本编排允许：")
                    .append(blockedReasons(blocked)).append("）");
        }
        text.append("：请启用并授权更多候选专家");
        return text.toString();
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
     * 按 route_key 定位候选（关键字命中用）。关键字选择器返回的是路由行本身，
     * 因此优先按 route_key 取；MA 候选没有 child_agent_id，只有这条路可靠。
     *
     * @param candidates 可用候选
     * @param route      关键字命中的路由行
     * @return 对应候选；键对不上时退回首位，保证不因脏数据中断一轮
     */
    private Candidate candidateOf(List<Candidate> candidates, AgentRoute route) {
        Candidate byKey = byRouteKey(candidates, route.getRouteKey());
        return byKey != null ? byKey : find(candidates, route.getChildAgentId());
    }

    /**
     * 按子 Agent 主键找候选（旧路由行兜底）。agentId 为空（MA 候选）时直接退回首位，
     * 不允许用 {@code equals(null)} 误命中。
     *
     * @param candidates 可用候选
     * @param childId    子 Agent 主键；可空
     * @return 命中的候选；找不到退回首位
     */
    private Candidate find(List<Candidate> candidates, Long childId) {
        if (childId != null) {
            for (Candidate item : candidates) {
                if (childId.equals(item.agent().getId())) {
                    return item;
                }
            }
        }
        return candidates.get(0);
    }

    /**
     * 判路由内部结果。所属域：runtime。
     *
     * @param intents    命中的意图（至少 1 项）
     * @param manual     true 表示本轮由用户手动换线直接命中，跳过了判路由
     * @param forceMissed true 表示手动换线的目标已失效，本轮回落正常判路由
     */
    private record RoutedChoice(List<RoutedIntent> intents, boolean manual, boolean forceMissed) {
    }

    /**
     * 一轮编排的执行结果。所属域：runtime。
     *
     * @param summary      路由说明（时间线首行）
     * @param decisionBlock 多意图决策块（人话：「已判定 N 个意图，按序分给…」）；单意图为空。
     *                      机器可读的 route_key JSON 只进 ROUTE span attrs，不在这里
     * @param segments     每个子的分段
     * @param notices      SYSTEM 说明
     * @param turn         终答
     * @param sticky       线路粘性回写指令；null 表示不改（固定模板 / 评审环 / 竞争选优）
     */
    public record RoutedTurn(String summary, String decisionBlock, List<RouteSegment> segments,
                             List<String> notices, TrialTurn turn, RouteSticky sticky) {
    }

    /**
     * 思考落库用的一个子 Agent 段。
     *
     * @param routeLine 「路由到 {名}（key），依据：…」
     * @param turn      该子执行结果；失败时可空
     * @param error     失败摘要；成功为 null
     * @param children  目标方案内部的下一层跳（「方案 → 各子专家」逐块展示用）；其余为空表
     */
    public record RouteSegment(String routeLine, TrialTurn turn, String error, List<NestedRoute> children) {

        /**
         * 兼容构造：普通子 Agent / 工作流目标没有下一层可展开。
         *
         * @param routeLine 「路由到 {名}（key），依据：…」
         * @param turn      该子执行结果；失败时可空
         * @param error     失败摘要；成功为 null
         */
        public RouteSegment(String routeLine, TrialTurn turn, String error) {
            this(routeLine, turn, error, List.of());
        }

        /** 下一层跳防御性归一：null → 空表，写时间线时不必逐处判空。 */
        public RouteSegment {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

}

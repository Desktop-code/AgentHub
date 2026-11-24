package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.runtime.application.router.AggregateOutcome;
import com.agenthub.runtime.application.router.AnswerAggregator;
import com.agenthub.runtime.application.router.Candidate;
import com.agenthub.runtime.application.router.Candidates;
import com.agenthub.runtime.application.router.CompeteTopology;
import com.agenthub.runtime.application.router.CriticLoopTopology;
import com.agenthub.runtime.application.router.FixedTopology;
import com.agenthub.runtime.application.router.RouterCandidateLoader;
import com.agenthub.runtime.application.router.RouterJudge;
import com.agenthub.runtime.application.router.RouterJudgePromptFactory;
import com.agenthub.runtime.application.router.RouterOutcomeAssembler;
import com.agenthub.runtime.application.router.RouterParallelTopology;
import com.agenthub.runtime.application.router.RouterThenSequenceTopology;
import com.agenthub.runtime.application.router.TurnNotes;
import com.agenthub.runtime.application.router.ChildOutcome;
import com.agenthub.runtime.application.router.ChildRunner;
import com.agenthub.runtime.application.router.MaNestedContext;
import com.agenthub.runtime.application.router.MaPauseScope;
import com.agenthub.runtime.application.router.MaPauseSignal;
import com.agenthub.runtime.application.router.MaTargetRunner;
import com.agenthub.runtime.application.router.NestedRoute;
import com.agenthub.runtime.application.router.RouteSpan;
import com.agenthub.runtime.application.router.RoutedIntent;
import com.agenthub.runtime.application.router.RouterClock;
import com.agenthub.runtime.application.router.RouterObs;
import com.agenthub.runtime.application.router.RouterTexts;
import com.agenthub.runtime.application.router.WorkflowTargetRunner;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.MaPauseMember;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.RouteHint;
import com.agenthub.runtime.domain.model.RouteSticky;
import com.agenthub.runtime.domain.model.ScratchBoard;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.ChildSubQueryInput;
import com.agenthub.runtime.domain.service.FixedSequenceContext;
import com.agenthub.runtime.domain.service.LeafChoiceHolder;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.ScratchContext;
import com.agenthub.runtime.domain.service.SoleChainHolder;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 参与者一个字都没产出时的终态说明。
     *
     * <p>为什么要落成正文而不是直接 return：卡片已经因为 begin 建出来了，没有正文也没有结束标记，
     * 前端只能一直显示「正在由「X」作答」直到整轮收尾。写一句明确说明，比让用户对着转圈猜更诚实，
     * 也不会把这类块伪装成「已完成作答」。
     */
    private static final String EMPTY_CONCLUSION_NOTE = "本轮未产出正文（该步骤已结束）。";

    /**
     * 读本轮「是否允许唯一叶子输出可点选项块」。
     *
     * @return true 仅当本轮由工作台显式开启；嵌套方案 / 调试 / 试跑一律 false
     */
    private static boolean notesLeafChoice() {
        TurnNotes notes = TurnNotes.current();
        return notes != null && notes.leafChoice;
    }

    /**
     * 本轮串行链是否允许建「停在成员询问处」的边界。
     *
     * <p>三条同时满足才开：
     * <ol>
     *   <li>工作台显式开启选项块（{@link #notesLeafChoice()}）；</li>
     *   <li>根层（depth 0）与一层嵌套（depth 1）——更深层没有恢复坐标，续跑只能还原最内层，
     *       中间方案的汇总与卡片收口都会缺，因此明确不开放；</li>
     *   <li>一层嵌套时，外层这一轮必须只派了它（{@link SoleChainHolder}）——多子链停住会丢兄弟步。</li>
     * </ol>
     *
     * @return true 表示可以给 ChildRunner 一个停等边界
     */
    private static boolean pauseScopeAllowed() {
        if (!notesLeafChoice()) {
            return false;
        }
        int depth = MaNestedContext.depth();
        if (depth == 0) {
            // 根层：与现网逐字一致，任何情况都维持原有停等口径
            return true;
        }
        return depth == 1 && SoleChainHolder.allows();
    }

    private final AgentRouteRepository routes;
    private final AgentDefRepository agents;
    /** 候选装载：旧路由行 / 方案候选表的过滤与去重口径都在这里，执行器只拿结果 */
    private final RouterCandidateLoader candidateLoader;
    /** 判路由提示词装配：提示词逐字与用户槽替换口径都在这里 */
    private final RouterJudgePromptFactory judgePrompts;
    /** 判路由决策：单问 / 多问 / 入口接待偏好 / 严格排序都在 {@code router} 包，本类只按下标回调 */
    private final RouterJudge judge;
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
    /** 分段 / 说明 / 跳过 / blocked / 汇总 / 粘性线路的组装口径，六套模板共用 */
    private final RouterOutcomeAssembler assembler;
    /** 固定模板拓扑：执行器只保留 `executeTemplates` 的早分支分派 */
    private final FixedTopology fixedTopology;
    /** ROUTER 并行拓扑：判路由之后的扇出执行体，判路由回调指向 {@link RouterJudge} */
    private final RouterParallelTopology routerParallelTopology;
    /** 路由后串行拓扑：判路由之后的串行接力，严格版判路由回调指向 {@link RouterJudge} */
    private final RouterThenSequenceTopology routerThenSequenceTopology;
    /** 评审环拓扑：跳过判路由的执行者 → 评审 → 回修串行链 */
    private final CriticLoopTopology criticLoopTopology;
    /** 竞争模板拓扑：跳过判路由的同题并行 → 选优，评委提示词与降级口径都在拓扑类里 */
    private final CompeteTopology competeTopology;

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
        this.candidateLoader = new RouterCandidateLoader(routes, agents);
        this.judgePrompts = new RouterJudgePromptFactory(prompts);
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
        // 判路由决策独立成 RouterJudge：模板拓扑只依赖一个「选谁 / 按什么顺序」的结果，
        // 提示词仍走已装配的 RouterJudgePromptFactory，执行器不再内联单问 / 多问 / 入口偏好
        this.judge = new RouterJudge(agents, react, judgePrompts, answers);
        // 子执行同样在构造器内装配：模板执行器只保留「选谁、按什么顺序跑」的编排决策；
        // 刀5 命中 MA 候选时用 this::runMaTarget 回到本执行器，刀5B 工作流候选走注入的实现
        this.children = new ChildRunner(loader, react, memory, planRunner, childApprovals, routerTaskExecutor,
                this::runMaTarget, workflowTargets);
        // 组装与固定模板拓扑同样在构造器内装配：直播出口与停等边界判定仍由本执行器持有，按回调注入
        this.assembler = new RouterOutcomeAssembler();
        this.fixedTopology = new FixedTopology(candidateLoader, children, answers, assembler,
                outlet -> childBodyOutlet(outlet, true), RouterTrialExecutor::pauseScopeAllowed);
        // ROUTER 并行拓扑同样在构造器内装配：判路由走 judge::choose（单问兜底顺序在其内部），
        // 直播出口与选项块开关按回调注入，不搬冻结面
        this.routerParallelTopology = new RouterParallelTopology(candidateLoader, children, answers, assembler,
                outlet -> childBodyOutlet(outlet, true), RouterTrialExecutor::notesLeafChoice, judge::choose);
        // 路由后串行与评审环同理：严格版判路由走 judge::chooseOrdered（无兜底，原样失败），
        // 直播出口 / 停等边界 / 根解析都留在本执行器，评审环的阶段正文用静音版出口
        this.routerThenSequenceTopology = new RouterThenSequenceTopology(candidateLoader, children, answers,
                assembler, outlet -> childBodyOutlet(outlet, true), RouterTrialExecutor::pauseScopeAllowed,
                judge::chooseOrdered);
        this.criticLoopTopology = new CriticLoopTopology(candidateLoader, children, answers, assembler,
                outlet -> childBodyOutlet(outlet, false), agents);
        // 竞争模板：同题并行 + 选优；本模板不判路由，评委提示词与降级顺序随拓扑一起搬，
        // 直播出口仍按回调留下，子正文照旧按身份增量出字
        this.competeTopology = new CompeteTopology(candidateLoader, children, answers, assembler,
                outlet -> childBodyOutlet(outlet, true), react, prompts);
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
     *   <li>有沿用线路时只在提示词里说明「优先沿用」。本轮选定后只说这一次分发，
     *       不再追加「已将线路改派」去改已经说出的入口句；</li>
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
        // 顶层调用不传观测父：方案根与 judge 继续父为空
        return execute(parent, spec, userText, modelUser, userId, sessionId, progress, hint, null);
    }

    /**
     * 同 {@link #execute(AgentDef, MaRunSpec, String, String, long, long, TrialProgress, RouteHint)}，
     * 并指定方案根的观测父。
     *
     * <p>只有 {@link #runMaTarget} 传入外壳 span。公开入口一律传 null，
     * 避免顶层单方案的根被挂到别的步骤下面。
     *
     * @param schemeObsParent 嵌套时的外壳 span；顶层为 null
     * @return 路由说明、分段、终答与粘性回写指令
     */
    private RoutedTurn execute(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                               long sessionId, TrialProgress progress, RouteHint hint, Long schemeObsParent) {
        // 本轮推理暂存：进入时换上一份新的，退出恢复（嵌套方案内联同线程时不能把外层的打掉）
        TurnNotes notes = new TurnNotes();
        /*
         * 「可点选项」开关从线程上下文读，而不是再加一个 execute 参数：
         * execute 是计划批准 / 试跑 / 工作台共用的编排入口，为一个只影响叶子提示词的开关
         * 改公开签名，会牵动所有既有调用点与桩。入口在调用前放置，这里只读一次。
         */
        notes.leafChoice = LeafChoiceHolder.enabled();
        TurnNotes previous = TurnNotes.swap(notes);
        try {
            RoutedTurn routed = executeScoped(parent, spec, userText, modelUser, userId, sessionId, progress, hint,
                    schemeObsParent);
            // 推理只补到返回值上供落库，路由结果与终答完全不受影响
            return routed.withNotes(notes.judgeReasoning.toString(), notes.conclusionName,
                    notes.conclusionReasoning.toString()).withGateway(notes.gateway);
        } finally {
            TurnNotes.restore(previous);
        }
    }

    /**
     * 串行方案停等后的续跑：从发出询问的那位成员继续，不判路由、不重写入口。
     *
     * <p>为什么单开一个入口而不是复用 {@link #execute}：续跑的输入不是「用户新问题」，
     * 而是「子问 + 已写结论 + 用户的回复」；再走一遍判路由会把这句话当新问题分给别人，
     * 时间线上还会多出「智能网关 → …」。这里只做三件事：
     * <ol>
     *   <li>用停等时的路由键从当前候选池重新取 AgentDef（停等期间被停用则明确失败）；</li>
     *   <li>把已跑完成员的结论作为前置摘要，把「你刚才问的问题 + 用户回复」交给同一位专家；</li>
     *   <li>按原顺序继续剩余成员；任一位再次询问时由 ChildRunner 抛新的停等信号。</li>
     * </ol>
     *
     * <p>返回列表第 0 位固定是「刚被回复的那位」，其后是剩余成员；调用方据此补写时间线：
     * 第 0 位的身份 / 路由已经写过，只追加它这次续跑新产生的思考与正文，避免卡片分裂成两张。
     *
     * @param parent    编排根；方案会话可空
     * @param spec      方案执行来源；可空表示旧 Agent 固定串行（本刀不主动产生停等）
     * @param state     从库里读出的停等快照
     * @param reply     用户回复文案（选项的服务端权威文案或输入框原文）
     * @param userId    会话归属人
     * @param sessionId 会话
     * @param progress  增量出口；可空（同步 send）
     * @return 续跑结果，顺序：被回复成员 + 剩余成员
     * @throws MaPauseSignal 后续成员再次发出询问
     * @throws TrialFailure  等待中的成员已停用 / 被删除，无法续跑
     */
    public List<ChildOutcome> resumeSerial(AgentDef parent, MaRunSpec spec, MaPauseSnapshot state, String reply,
                                           long userId, long sessionId, TrialProgress progress) {
        boolean schemeTurn = spec != null;
        AgentRuntimeConfig runtime = schemeTurn
                ? spec.runtime()
                : AgentRuntimeConfig.parse(parent.getRuntimeConfigJson(), parent.getRunMode());
        EffectiveLimits parentLimits = limitResolver.resolve(parent);
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, "resume-sequence", rootId(parent));
        try {
            RouterClock.assertBefore(deadlineNanos);
            // 候选按当前配置重新装载：停等期间被停用的成员不应继续跑，也不静默换成别人
            Candidates loaded = candidateLoader.load(rootId(parent), runtime, spec);
            Map<String, Candidate> byKey = new LinkedHashMap<>();
            for (Candidate candidate : loaded.usable()) {
                String key = routeKeyOf(candidate);
                if (!key.isEmpty()) {
                    byKey.putIfAbsent(key, candidate);
                }
            }
            RoutedIntent first = resumeIntent(state.getPausedMember(), byKey);
            if (first == null) {
                throw new TrialFailure("等待中的专家「" + state.getPausedMember().getParticipantName()
                        + "」已停用或被移出方案，无法继续这条运行；请重新提问或调整方案候选");
            }
            List<RoutedIntent> intents = new ArrayList<>();
            intents.add(first);
            for (MaPauseMember member : state.getRemaining()) {
                RoutedIntent intent = resumeIntent(member, byKey);
                if (intent == null) {
                    // 已停用的剩余成员不冒充跑过：只记日志，由调用方在时间线留一句跳过说明
                    log.info("方案续跑跳过已停用成员 sessionId={} routeKey={}", sessionId, member.getRouteKey());
                    continue;
                }
                intents.add(intent);
            }
            // 已完成成员照旧作为前置摘要；被回复的那位自己的旧答放进 override，不重复占摘要名额
            List<FixedSequenceContext.PriorStep> prior = new ArrayList<>();
            for (MaPauseMember member : state.getDone()) {
                prior.add(new FixedSequenceContext.PriorStep(member.getParticipantName(),
                        member.getAnswer(), member.isFailed()));
            }
            String firstModelText = resumeModelText(state, prior, reply);
            /*
             * 同一条串行链继续：scope 带上已写键，后续若再次询问，停等补写不会把已写过的人重写一遍。
             * 这里不重新读 LeafChoiceHolder：续跑本身就是「等待中的运行继续」，选项能力沿用原轮口径。
             */
            List<ChildOutcome> outcomes = children.runFixedSequence(parent, state.getModelUser(),
                    state.getUserText(), userId, sessionId, deadlineNanos, progress, trace,
                    ChildRunner.childAgentParent(trace, route.id()), intents, runtime,
                    MaPauseScope.resume(state), prior, firstModelText, true);
            route.ok("串行方案续跑", null, null, "从等待成员继续", java.util.Map.of());
            return outcomes;
        } catch (MaPauseSignal pause) {
            // 后续成员又问了一次：不按失败处理，交给调用方再落一次等待态
            route.ok("方案再次停等", null, null, "后续成员等待用户回复", java.util.Map.of());
            throw pause;
        } catch (RuntimeException ex) {
            log.warn("串行方案续跑失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            route.error("串行方案续跑失败", ex);
            throw ex;
        } finally {
            RouteSpan.unbind(trace);
        }
    }

    /**
     * 续跑第一位的模型输入：本步子问 + 已写结论 + 刚才的提问 + 用户回复。
     *
     * <p>为什么把「刚才的提问」也带上：续跑是一次全新的模型调用，不给它问句，它只会看到
     * 「用户说 2」，无法判断这是在回答哪个问题；重复问一遍又会打断等待语义。
     *
     * @param state  停等快照
     * @param prior  已完成成员的结论
     * @param reply  用户回复
     * @return 送给同一位专家的完整输入
     */
    private static String resumeModelText(MaPauseSnapshot state, List<FixedSequenceContext.PriorStep> prior,
                                          String reply) {
        MaPauseMember paused = state.getPausedMember();
        String question = paused == null || paused.getSubQuery() == null || paused.getSubQuery().isBlank()
                ? state.getUserText() : paused.getSubQuery();
        StringBuilder text = new StringBuilder(FixedSequenceContext.render(question, prior));
        String asked = state.getChoice() == null ? "" : state.getChoice().question();
        if (!asked.isBlank()) {
            text.append("\n\n【你刚才向用户提出的问题】\n").append(asked.trim());
        }
        String before = state.getPausedAnswer() == null ? "" : state.getPausedAnswer().trim();
        if (!before.isEmpty()) {
            text.append("\n\n【你在提问前已经写出的内容】\n").append(before);
        }
        text.append("\n\n【用户已经回复】\n").append(reply == null ? "" : reply.trim());
        text.append("\n\n请直接依据用户这次回复继续完成你的任务，不要再重复同一个问题。");
        return text.toString();
    }

    /**
     * 从当前候选池还原一位成员的执行意图；候选已不可用时返回 null。
     *
     * @param member 停等快照里的成员
     * @param byKey  路由键 → 候选
     * @return 可执行意图；找不到候选时 null
     */
    private static RoutedIntent resumeIntent(MaPauseMember member, Map<String, Candidate> byKey) {
        if (member == null) {
            return null;
        }
        String key = member.getRouteKey() == null ? "" : member.getRouteKey().trim();
        Candidate candidate = key.isEmpty() ? null : byKey.get(key);
        if (candidate == null) {
            return null;
        }
        return new RoutedIntent(candidate, member.getSubQuery(), member.getReason(), member.getRouteLine());
    }

    /**
     * 候选的路由键；没有 route_key 时退回 Agent 主键（与参与者 id 的兜底一致）。
     *
     * @param candidate 候选
     * @return 非空键
     */
    private static String routeKeyOf(Candidate candidate) {
        String key = candidate.route().getRouteKey();
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        return candidate.agent() == null || candidate.agent().getId() == null
                ? "" : "#" + candidate.agent().getId();
    }

    /**
     * 一轮编排的实际执行体。所属域：runtime。
     *
     * <p>与 {@link #execute} 分开的原因只有一个：入口负责「推理暂存的进入 / 退出」，
     * 执行语义（模板 → 判路由 → 子 Agent → 汇总）保持原样。
     */
    private RoutedTurn executeScoped(AgentDef parent, MaRunSpec spec, String userText, String modelUser,
                                     long userId, long sessionId, TrialProgress progress, RouteHint hint,
                                     Long schemeObsParent) {
        EffectiveLimits parentLimits = limitResolver.resolve(parent);
        // 方案优先：模板与上下文策略以 ma_def.config_json 为准，避免执行时又读回 Agent 镜像
        AgentRuntimeConfig runtime = spec != null
                ? spec.runtime()
                : AgentRuntimeConfig.parse(parent.getRuntimeConfigJson(), parent.getRunMode());
        // 共享草稿区（MA-P1 刀7）：只有选中 SHARED_SCRATCH 才开板；板是「本轮 run 级」状态，
        // 挂在线程上下文里随根执行传下去，根结束（成功 / 失败 / 取消）后 close 丢弃，不落库、不跨轮复用
        ScratchBoard scratch = runtime.scratchOn() ? new ScratchBoard() : null;
        // 入口参与者「真正开始」：先于本次判路由的任何思考，前端据此立刻画出入口块。
        // 只在最外层推：嵌套方案已有自己的 scheme begin，不能再多一个同名的入口块。
        if (progress != null && MaNestedContext.depth() == 0) {
            ParticipantView gateway = ParticipantView.gateway(
                    "gw-" + (rootId(parent) == null ? "ma-" + (spec == null ? "0" : spec.maId()) : rootId(parent)),
                    rootName(parent, spec));
            progress.beginParticipant(gateway);
            // 与 SSE 同一份身份，供时间线在入口思考之前落一行 participant
            TurnNotes current = TurnNotes.current();
            if (current != null) {
                current.gateway = gateway;
            }
        }
        if (scratch == null) {
            return executeRun(parent, spec, userText, modelUser, userId, sessionId, progress, runtime, parentLimits,
                    hint, schemeObsParent);
        }
        try {
            return ScratchContext.callAs(scratch, "", () ->
                    executeRun(parent, spec, userText, modelUser, userId, sessionId, progress, runtime, parentLimits,
                            hint, schemeObsParent));
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
        // 否则本轮后续的汇总 / 投票 LLM 会挂到编排根而不是 ROUTE。
        // previousParent 就是外壳 span（子执行开外壳后已 bind），嵌套方案根挂在它下面。
        Long previousParent = trace == null ? null : trace.implicitParent();
        // 外层模型步先摘下来：内层没开 LLM 时不能顺着「最近一条」挂回入口 judge
        ObsTraceScope.LlmCursor outerLlm = trace == null ? null : trace.captureLlmCursor();
        if (trace != null) {
            trace.restoreLlmCursor(null);
        }
        // 进入方案层时记下「这一层是哪个方案」：内层子专家的参与者 id 要带它，
        // 否则两层方案都分同名 route_key 时会被 putIfAbsent 认成同一个人，第二张卡不出。
        String schemeRouteKey = intent == null || intent.candidate() == null || intent.candidate().route() == null
                ? "" : intent.candidate().route().getRouteKey();
        MaNestedContext.enter(schemeRouteKey);
        try {
            // parent 传 null：目标方案不需要任何 Agent 根，执行器按 spec 装配。
            // 观测父传外壳：只有这一层嵌套才把方案根挂上去，顶层调用仍传 null。
            RoutedTurn nested = execute(null, target, userText, modelUser, userId, sessionId, progress, null,
                    previousParent);
            List<String> notices = new ArrayList<>();
            // 目标方案内部每一跳原样带出去：时间线按「方案 → 各子专家」逐块展示，
            // 不再压成一条「子编排内部：路由到…」的说明句（那会让参与者全部隐身）
            List<NestedRoute> children = new ArrayList<>();
            if (nested.segments() != null) {
                for (RouteSegment segment : nested.segments()) {
                    children.add(new NestedRoute(segment.routeLine(), segment.turn(), segment.error(),
                            segment.participant()));
                }
            }
            if (nested.notices() != null) {
                notices.addAll(nested.notices());
            }
            return new MaTargetRunner.MaTargetResult(nested.turn(), notices, children, nested.judgeReasoning(),
                    nested.conclusionName(), nested.conclusionReasoning());
        } finally {
            MaNestedContext.exit();
            if (trace != null) {
                // 写回外层模型步，再把栈父还原到外壳；内层按父索引的 LLM 留在表里，不跟这次写回一起清掉
                trace.restoreLlmCursor(outerLlm);
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
     * @param hint            线路提示；可空（调试 / 试跑恒空）
     * @param schemeObsParent 方案根的观测父；顶层 null，嵌套时为外壳 span
     * @return 路由说明、分段与终答
     */
    private RoutedTurn executeRun(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                                  long sessionId, TrialProgress progress, AgentRuntimeConfig runtime,
                                  EffectiveLimits parentLimits, RouteHint hint, Long schemeObsParent) {
        // 方案以自己为根：开一个只读「编排根」步骤，泳道才有根，attrs 才能带 ma.def_id / code / template。
        // 父只在嵌套时指向外壳；顶层 schemeObsParent 为 null，根继续父为空。
        ObsTraceScope trace = ObsTraceHolder.current();
        Long maRootSpan = null;
        if (trace != null && spec != null) {
            maRootSpan = trace.open(SpanType.AGENT, RouterObs.maRootName(spec), null, schemeObsParent, null);
            trace.bindParent(maRootSpan);
        }
        RuntimeException failure = null;
        // 只有入口智能网关把 judge 挂到网关根上。普通单方案保持父空，退出时还原，不把开关留在内层。
        boolean previousHang = RouteSpan.judgeHangsOnRoot();
        RouteSpan.judgeHangsOnRoot(RouterJudge.isEntryRouter(spec));
        try {
            return executeTemplates(parent, spec, userText, modelUser, userId, sessionId, progress, runtime,
                    parentLimits, hint);
        } catch (MaPauseSignal pause) {
            // 停等不是失败：failure 保持 null，finally 按 OK 收编排根，由入口落等待态
            throw pause;
        } catch (RuntimeException ex) {
            // 虚拟根编排根同样要收尾：失败帧带脱敏 cause，泳道第一行就能定位
            failure = ex;
            throw ex;
        } finally {
            RouteSpan.judgeHangsOnRoot(previousHang);
            RouterObs.closeMaRoot(trace, maRootSpan, spec, failure);
        }
    }

    /**
     * 六模板分派与判路由主链（虚拟根 / 绑根共用）。所属域：runtime。
     *
     * <p>本方法只管执行语义，编排根的观测壳由 {@link #executeRun} 负责，
     * 因此这里不再重复开关根步骤，父子链与拆前完全一致。
     *
     * <p>执行体去向：评审环 / 竞争 / 固定模板 / 路由后串行 / ROUTER 并行都已各自成拓扑类
     * （{@link CriticLoopTopology} / {@link CompeteTopology} / {@link FixedTopology} /
     * {@link RouterThenSequenceTopology} / {@link RouterParallelTopology}）。
     * 这里只把判路由回调（{@link RouterJudge}）、直播出口、选项块开关与根解析
     * 作为现场 / 回调交出去，早分支顺序不变：评审环 → 竞争 → 固定模板 → 路由后串行 → 路由并行。
     */
    private RoutedTurn executeTemplates(AgentDef parent, MaRunSpec spec, String userText, String modelUser,
                                        long userId, long sessionId, TrialProgress progress,
                                        AgentRuntimeConfig runtime, EffectiveLimits parentLimits, RouteHint hint) {
        // 模板早分支：评审环与固定模板都不判路由，整条执行链与判路由路径完全分开，避免语义互相污染
        if (runtime.isCriticLoop()) {
            // 评审环不判路由：执行者 / 评审者 / 轮次口径都在拓扑类里，根 id 仍按执行器解析后传入
            return criticLoopTopology.execute(parent, spec, userText, userId, sessionId, progress, runtime,
                    parentLimits, rootId(parent));
        }
        // 竞争选优：同题并发 → 选优取 1 个子终答；不判路由、不做多段拼接
        if (runtime.isCompete()) {
            return competeTopology.execute(parent, spec, userText, modelUser, userId, sessionId, progress, runtime,
                    parentLimits, rootId(parent), rootName(parent, spec));
        }
        if (runtime.isFixedTemplate()) {
            return executeFixed(parent, spec, userText, modelUser, userId, sessionId, progress, runtime,
                    parentLimits);
        }
        // 路由后串行：判路由契约与 ROUTER_PARALLEL 完全相同，只是把扇出换成「按执行顺序串行」。
        // 严格版 chooseOrdered 在 RouterJudge（无兜底，判路由失败即整次失败）
        if (runtime.isRouterThenSequence()) {
            return routerThenSequenceTopology.execute(parent, spec, userText, modelUser, userId, sessionId, progress,
                    runtime, parentLimits, rootId(parent), rootName(parent, spec));
        }
        // ROUTER 并行模板：判路由 + 扇出的执行体已搬到 RouterParallelTopology。
        // 判路由走 RouterJudge（点名 → 关键字 → 职责覆盖 → 模型 → 第一条兜底不变），
        // 直播出口 / 选项块开关 / 根解析也留在本类，早分支顺序与返回形状不变。
        return routerParallelTopology.execute(parent, spec, userText, modelUser, userId, sessionId, progress,
                runtime, parentLimits, hint, rootId(parent), rootName(parent, spec));
    }

    /**
     * 固定模板执行（FIXED_PARALLEL / FIXED_SEQUENCE）的分派。所属域：runtime。
     *
     * <p>执行体已搬到 {@link FixedTopology}：候选装载、并行上限、跳过、停等边界、执行、ROUTE 收尾
     * 与汇总都在拓扑类里，口径与拆前逐字一致。这里只把本轮编排现场（根 id / 根名 / 墙钟）交出去，
     * `executeTemplates` 的早分支顺序与返回形状不变。
     *
     * @param runtime       已解析的运行配置（模板与上下文策略从这里读）
     * @param parentLimits  父墙钟与轮次上限
     * @return 路由说明、分段与汇总后的终答
     */
    private RoutedTurn executeFixed(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                                    long sessionId, TrialProgress progress, AgentRuntimeConfig runtime,
                                    EffectiveLimits parentLimits) {
        return fixedTopology.execute(parent, spec, userText, modelUser, userId, sessionId, progress, runtime,
                parentLimits, rootId(parent), rootName(parent, spec));
    }

    /**
     * 子正文出口：直播时按身份转发正文增量，收尾只补「这一段还没流过」的结论。
     *
     * <p>为什么不再整段静音：旧实现把多子 / SUMMARIZE / VOTE 下每个子的 {@code assistant}
     * 全部丢掉，等该子结束才补一次整段。页面表现就是「泳道转很久 → 所有子专家同一拍冒出整段」，
     * 直播期间一个字都没有。前端早已按 {@code participantId} 分块（同一位参与者才合并），
     * 交错到达的增量会被切回各自分块，因此可以边生成边推。
     *
     * <p>不重影：收尾的 {@link TrialProgress#deliverParticipantConclusion(String, String)}
     * 只在「这一位一个增量都没推过」时才整段补（模型没走流式、或子只产出了工具步）。
     * 判定按参与者 id 记，并按线程保存当前归属——并行子各在各自线程上推，互不串味。
     *
     * @param progress 下游进度；可空（窄测 / 不展示进度）
     * @param liveBody true 表示转发正文增量（子专家边跑边出字）；
     *                 false 表示仍然整段静音（评审环：初稿若流出，回修后会变成两版答复）
     */
    private TrialProgress childBodyOutlet(TrialProgress progress, boolean liveBody) {
        if (progress == null) {
            return null;
        }
        /*
         * 当前归属 + 已出过正文的参与者。归属用 ThreadLocal：participantBound 保证
         * 「先 attribute 再转发」且与增量同线程顺序执行；集合用并发实现是因为并行子
         * 会在多个线程上同时登记。
         */
        ThreadLocal<String> current = new ThreadLocal<>();
        java.util.Set<String> streamed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        // 本线程上这一位是否已经把正文推出去过：收尾据此决定要不要整段补
        ThreadLocal<Boolean> bodyStreamed = ThreadLocal.withInitial(() -> Boolean.FALSE);
        return new TrialProgress() {
            @Override
            public void phase(String phase) {
                progress.phase(phase);
            }

            @Override
            public void route(String summary) {
                progress.route(summary);
            }

            /** 参与者开始必须原样透传：这里只是正文出口，不是分块信号。 */
            @Override
            public void beginParticipant(ParticipantView participant) {
                // 换了一位：本线程的「已出正文」重新判定，避免串到上一位的结论去
                bodyStreamed.set(Boolean.FALSE);
                progress.beginParticipant(participant);
            }

            /** 归属切换同样透传，同时记下本线程当前归属，供收尾判重影。 */
            @Override
            public void attributeParticipant(String participantId) {
                current.set(participantId == null ? "" : participantId);
                progress.attributeParticipant(participantId);
            }

            @Override
            public void reasoning(String delta) {
                progress.reasoning(delta);
            }

            /**
             * 归属刚推出去那条 route 的思考：正文出口只压 / 放正文，推理必须透传；
             * 用 pinned 通道才不会被下游的泳道包装器再重推一次泳道路由。
             */
            @Override
            public void reasoningPinned(String delta) {
                progress.reasoningPinned(delta);
            }

            @Override
            public void toolStart(String name) {
                // 工具开始：同样转发，让页面先看到「正在调用」胶囊
                progress.toolStart(name);
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
                progress.tool(name, arguments, result, success);
            }

            /**
             * 正文增量：直播路径按身份原样转发，并登记「这一位已经出过字」。
             *
             * <p>静音路径（评审环）仍然丢掉：初稿流出后回修，时间线上会留下两版答复。
             */
            @Override
            public void assistant(String delta) {
                if (delta == null || delta.isEmpty() || !liveBody) {
                    return;
                }
                String id = current.get();
                if (id != null && !id.isBlank()) {
                    streamed.add(id);
                }
                bodyStreamed.set(Boolean.TRUE);
                progress.assistant(delta);
            }

            /**
             * 参与者结束时的整段结论：已经按增量流过的**不重复补**，否则同一段正文出现两次。
             * 只有本线程这一位一个字都没流过（非流式模型 / 只有工具步 / 被撞限打断）才整段补。
             *
             * <p><b>空结论也要收口</b>：调用方现在对空正文也会调用本方法。一个字都没有时
             * 不能直接 return——否则这张卡既没有正文、也没有结束标记，前端只能等整轮 done，
             * 表现就是「一直转圈，汇总落屏时前面的空壳才一起闪现」。这里补一句明确的终态说明。
             */
            @Override
            public void deliverParticipantConclusion(String routeLine, String text) {
                if (!liveBody) {
                    // 评审环等静音口径不变：不补正文，也不在这里造终态说明
                    return;
                }
                String id = current.get();
                boolean alreadyStreamed = Boolean.TRUE.equals(bodyStreamed.get())
                        || (id != null && !id.isBlank() && streamed.contains(id));
                if (alreadyStreamed) {
                    return;
                }
                if (routeLine != null && !routeLine.isBlank()) {
                    progress.route(routeLine);
                }
                String body = text == null || text.isBlank() ? EMPTY_CONCLUSION_NOTE : text.trim();
                progress.assistant(body);
            }

            /**
             * 参与者终态原样透传：这里只压正文，不压「这一步结束了」这个事实。
             */
            @Override
            public void endParticipant(ParticipantView participant, String state, String note) {
                progress.endParticipant(participant, state, note);
            }

            /**
             * 内层方案要能写出子结论和汇总结论，不能再套一层外层正文出口。
             */
            @Override
            public TrialProgress conclusionChannel() {
                return progress.conclusionChannel();
            }

            /**
             * 产物必须原样透传：这里压的是正文，不是文件生成。
             * 漏掉这个覆写会让并行 / 汇总路径下的下载卡片凭空消失（默认实现是空操作）。
             */
            @Override
            public void file(GeneratedFile file) {
                progress.file(file);
            }

            /**
             * 结构化选项同样原样透传：它是「本步结果」，不是正文增量；
             * 被静音吞掉的话，汇总模板下用户就永远看不到 chips。
             */
            @Override
            public void choice(AgentChoice choice) {
                progress.choice(choice);
            }

            /**
             * 子计划待批必须原样透传：静音只针对助手正文，批准气泡不能被吞掉。
             */
            @Override
            public void childPlanPending(ChildPlanPendingView pending) {
                progress.childPlanPending(pending);
            }

            /**
             * 工作流执行卡片必须原样透传：这里压的是「并行子正文」，不是这张卡片本身。
             * 漏掉这个覆写会让多意图 / 汇总轮里的工作流节点时间轴完全不出现。
             */
            @Override
            public void workflow(WorkflowRunView view) {
                progress.workflow(view);
            }
        };
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
     * 判路由内部结果。所属域：runtime。
     *
     * <p>public 是因为产出方 {@code runtime.application.router.RouterJudge} 与消费方
     * {@code RouterParallelTopology} 都在 router 包；字段与兼容构造保持拆前语义，
     * 本刀不搬 DTO，仍随执行器一起演进。
     *
     * @param intents    命中的意图（至少 1 项）
     * @param manual     true 表示本轮由用户手动换线直接命中，跳过了判路由
     * @param forceMissed true 表示手动换线的目标已失效，本轮回落正常判路由
     */
    public record RoutedChoice(List<RoutedIntent> intents, boolean manual, boolean forceMissed,
                               boolean multiQuestion) {

        /**
         * 兼容构造：单问选择（点名 / 关键字 / 覆盖 / 模型 / 兜底）都不带「多问拆分」标记。
         *
         * @param intents    命中意图
         * @param manual     是否手动换线
         * @param forceMissed 强制目标是否失效
         */
        private RoutedChoice(List<RoutedIntent> intents, boolean manual, boolean forceMissed) {
            this(intents, manual, forceMissed, false);
        }
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
     * @param judgeReasoning 判路由模型这一轮的推理（入口块 / 方案分流块的思考）；落库后刷新仍可见
     * @param conclusionName 真汇总记录名（方案名）；没有真汇总时为 null
     * @param conclusionReasoning 汇总模型的推理；只在该步真的开始时才非空
     */
    public record RoutedTurn(String summary, String decisionBlock, List<RouteSegment> segments,
                             List<String> notices, TrialTurn turn, RouteSticky sticky,
                             String judgeReasoning, String conclusionName, String conclusionReasoning,
                             ParticipantView gateway) {

        /**
         * 兼容构造：多数模板分支不判路由、也没有真汇总。
         *
         * @param summary       路由说明
         * @param decisionBlock 多意图决策块
         * @param segments      分段
         * @param notices       说明
         * @param turn          终答
         * @param sticky        线路回写指令
         */
        public RoutedTurn(String summary, String decisionBlock, List<RouteSegment> segments,
                          List<String> notices, TrialTurn turn, RouteSticky sticky) {
            this(summary, decisionBlock, segments, notices, turn, sticky, null, null, null, null);
        }

        /**
         * 挂上本轮判定与汇总的推理（只用于落库，不改路由结果）。
         *
         * @param judge      判路由推理；可空
         * @param name       汇总记录名；可空
         * @param conclusion 汇总推理；可空
         * @return 补齐推理后的结果
         */
        public RoutedTurn withNotes(String judge, String name, String conclusion) {
            return new RoutedTurn(summary, decisionBlock, segments, notices, turn, sticky,
                    judge == null || judge.isBlank() ? null : judge,
                    name == null || name.isBlank() ? null : name,
                    conclusion == null || conclusion.isBlank() ? null : conclusion,
                    gateway);
        }

        /**
         * 挂上入口身份。嵌套方案没有入口 begin，传 null 时保持原值。
         *
         * @param who 入口参与者；可空
         * @return 带入口身份的结果
         */
        public RoutedTurn withGateway(ParticipantView who) {
            if (who == null) {
                return this;
            }
            return new RoutedTurn(summary, decisionBlock, segments, notices, turn, sticky,
                    judgeReasoning, conclusionName, conclusionReasoning, who);
        }
    }

    /**
     * 思考落库用的一个子 Agent 段。
     *
     * @param routeLine 「路由到 {名}（key），依据：…」
     * @param turn      该子执行结果；失败时可空
     * @param error     失败摘要；成功为 null
     * @param children  目标方案内部的下一层跳（「方案 → 各子专家」逐块展示用）；其余为空表
     * @param reasoning 该段自己的判定推理（只有方案分流会有：方案在分给子专家前想的那一段）
     * @param workflow  工作流目标的执行卡片终态（只有 WORKFLOW 目标会非空）；落库后刷新仍能看卡片
     * @param conclusionName      目标方案自己的汇总结论名；没有真汇总时为 null
     * @param conclusionReasoning 目标方案汇总模型的推理；随「方案结论：X」一起落库
     * @param participant         开跑时的身份；与 SSE begin 同一份。没开跑（跳过 / 说明行）为 null
     */
    public record RouteSegment(String routeLine, TrialTurn turn, String error, List<NestedRoute> children,
                               String reasoning, WorkflowRunView workflow, String conclusionName,
                               String conclusionReasoning, ParticipantView participant) {

        /**
         * 兼容构造：普通子 Agent / 工作流目标没有下一层可展开，也没有自己的判定推理。
         *
         * @param routeLine 「路由到 {名}（key），依据：…」
         * @param turn      该子执行结果；失败时可空
         * @param error     失败摘要；成功为 null
         */
        public RouteSegment(String routeLine, TrialTurn turn, String error) {
            this(routeLine, turn, error, List.of(), null, null, null, null, null);
        }

        /**
         * 兼容构造：有下一层但没有自己的判定推理。
         *
         * @param routeLine 「路由到 {名}（key），依据：…」
         * @param turn      该子执行结果；失败时可空
         * @param error     失败摘要；成功为 null
         * @param children  下一层跳
         */
        public RouteSegment(String routeLine, TrialTurn turn, String error, List<NestedRoute> children) {
            this(routeLine, turn, error, children, null, null, null, null, null);
        }

        /**
         * 兼容构造：有判定推理、没有工作流卡片（方案分流走这条）。
         *
         * @param routeLine 路由行
         * @param turn      子执行结果
         * @param error     失败摘要
         * @param children  下一层跳
         * @param reasoning 本段判定推理
         */
        public RouteSegment(String routeLine, TrialTurn turn, String error, List<NestedRoute> children,
                            String reasoning) {
            this(routeLine, turn, error, children, reasoning, null, null, null, null);
        }

        /**
         * 兼容构造：有工作流卡片、没有目标方案汇总结论（工作流段与既有调用点走这条）。
         *
         * @param routeLine 路由行
         * @param turn      子执行结果
         * @param error     失败摘要
         * @param children  下一层跳
         * @param reasoning 本段判定推理
         * @param workflow  卡片终态
         */
        public RouteSegment(String routeLine, TrialTurn turn, String error, List<NestedRoute> children,
                            String reasoning, WorkflowRunView workflow) {
            this(routeLine, turn, error, children, reasoning, workflow, null, null, null);
        }

        /**
         * 兼容构造：结论名已有、身份稍后用 {@link #withParticipant} 补。
         *
         * @param conclusionName      汇总结论名
         * @param conclusionReasoning 汇总推理
         */
        public RouteSegment(String routeLine, TrialTurn turn, String error, List<NestedRoute> children,
                            String reasoning, WorkflowRunView workflow, String conclusionName,
                            String conclusionReasoning) {
            this(routeLine, turn, error, children, reasoning, workflow, conclusionName, conclusionReasoning, null);
        }

        /**
         * 挂上工作流卡片终态（不改其它字段）。
         *
         * @param view 卡片终态；null 时原样返回
         * @return 带卡片的段
         */
        public RouteSegment withWorkflow(WorkflowRunView view) {
            return view == null ? this
                    : new RouteSegment(routeLine, turn, error, children, reasoning, view,
                            conclusionName, conclusionReasoning, participant);
        }

        /**
         * 挂上开跑时的身份（不改其它字段）。
         *
         * @param who 参与者身份；null 时原样返回
         * @return 带身份的段
         */
        public RouteSegment withParticipant(ParticipantView who) {
            return who == null ? this
                    : new RouteSegment(routeLine, turn, error, children, reasoning, workflow,
                            conclusionName, conclusionReasoning, who);
        }

        /** 下一层跳防御性归一：null → 空表，写时间线时不必逐处判空。 */
        public RouteSegment {
            children = children == null ? List.of() : List.copyOf(children);
            reasoning = reasoning == null || reasoning.isBlank() ? null : reasoning;
            conclusionName = conclusionName == null || conclusionName.isBlank() ? null : conclusionName;
            conclusionReasoning =
                    conclusionReasoning == null || conclusionReasoning.isBlank() ? null : conclusionReasoning;
        }
    }
}

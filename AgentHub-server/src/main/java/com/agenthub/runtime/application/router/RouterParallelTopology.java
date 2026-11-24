package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.RouterAnswerMode;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.runtime.application.RouterTrialExecutor.RouteSegment;
import com.agenthub.runtime.application.RouterTrialExecutor.RoutedChoice;
import com.agenthub.runtime.application.RouterTrialExecutor.RoutedTurn;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RouteHint;
import com.agenthub.runtime.domain.model.RouteSticky;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.RouteDecisionParser;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * ROUTER 并行拓扑（{@code ROUTER_PARALLEL}）。所属域：runtime。
 *
 * <p>从 {@code RouterTrialExecutor.executeTemplates} 的尾部（早分支之后的判路由 + 并行扇出）搬出：
 * 候选装载、判路由调用、意图截断、路由行 / 决策块、子并行执行、终答汇总与 ROUTE 收尾全部收在本类；
 * 执行器只在早分支之后留一行分派，四个早分支（评审环 / 竞争 / 固定模板 / 路由后串行）顺序不变。
 *
 * <p>判路由家族（{@code choose} / {@code chooseForQuestions} / {@code chooseOrdered} / 提示词装配）
 * 已搬到 {@link RouterJudge}：本类通过 {@link RouteChooser} 回调调用，因此单问失败仍按
 * 点名 → 关键字 → 职责覆盖 → 模型 → 第一条兜底回落，不会退化成「无兜底、失败即整次失败」。
 *
 * <p>明确不搬：
 * <ul>
 *   <li>子正文出口 {@code childBodyOutlet}：直播契约，仍由执行器持有，这里按注入的单参数版本使用；</li>
 *   <li>选项块开关 {@code notesLeafChoice}：仍由执行器判定，这里按回调读；</li>
 *   <li>根 id / 根名解析：沿用执行器，作为一次执行的现场传入，避免两处各算一份；</li>
 *   <li>{@link RoutedTurn} / {@link RouteSegment} / {@link RoutedChoice}：类型仍留在执行器。</li>
 * </ul>
 */
public final class RouterParallelTopology {

    private static final Logger log = LoggerFactory.getLogger(RouterParallelTopology.class);

    /** 候选装载：旧路由行 / 方案候选表的过滤与去重口径都在这里 */
    private final RouterCandidateLoader candidateLoader;
    /** 子 Agent 并行扇出执行 */
    private final ChildRunner children;
    /** 终答汇总（CONCAT / SUMMARIZE / VOTE）与多问分答 */
    private final AnswerAggregator answers;
    /** 分段 / 说明 / blocked / 汇总 / 粘性线路的组装口径 */
    private final RouterOutcomeAssembler assembler;
    /** 子正文出口：执行器把 {@code childBodyOutlet(progress, true)} 包成单参数传进来（直播契约不搬） */
    private final Function<TrialProgress, TrialProgress> liveOutlet;
    /** 本轮是否允许唯一叶子输出可点选项块；口径仍由执行器判定 */
    private final BooleanSupplier leafChoiceAllowed;
    /** 判路由入口：决策在 {@link RouterJudge}，这里只按接口调用 */
    private final RouteChooser chooser;

    /**
     * 判路由回调：把 {@link RouterJudge#choose} 接到拓扑里，签名与拆前逐字一致。
     *
     * <p>实现方=RouterJudge；调用方=本拓扑。单问回落顺序（点名 → 关键字 → 职责覆盖 → 模型 → 第一条）
     * 与多问切分都在实现方，本类不做任何兜底或重判。
     */
    @FunctionalInterface
    public interface RouteChooser {

        /**
         * 判一次路由。
         *
         * @param parent        编排根；旧 Agent 试跑为 ROUTER，方案运行可为 null
         * @param spec          方案执行来源；可空
         * @param parentLimits  父墙钟与轮次上限（判路由模型的实际超时由实现方再收窄）
         * @param runtime       已解析的运行配置（多意图开关 / 上限 / 判定模型从这里读）
         * @param userText      用户原问全文
         * @param candidates    本轮可用候选（已过滤门闩）
         * @param userId        平台用户
         * @param sessionId     当前会话
         * @param deadlineNanos 父墙钟截止（{@link System#nanoTime()} 口径）
         * @param hint          线路提示（沿用 / 强制换线）；可空
         * @param progress      可空思考出口
         * @return 命中的意图与「手动 / 强制失效 / 多问」三个标记
         * @throws TrialFailure 判路由不可用或墙钟超时（非超时失败由实现方回落第一条）
         */
        RoutedChoice choose(AgentDef parent, MaRunSpec spec, EffectiveLimits parentLimits,
                            AgentRuntimeConfig runtime, String userText, List<Candidate> candidates,
                            long userId, long sessionId, long deadlineNanos, RouteHint hint,
                            TrialProgress progress);
    }

    /**
     * @param candidateLoader    候选装载
     * @param children           子执行（并行扇出）
     * @param answers            终答汇总
     * @param assembler          结果组装
     * @param liveOutlet         子正文直播出口（单参数包装）
     * @param leafChoiceAllowed  是否允许唯一叶子输出选项块
     * @param chooser            判路由回调（实现方为执行器）
     */
    public RouterParallelTopology(RouterCandidateLoader candidateLoader, ChildRunner children,
                                  AnswerAggregator answers, RouterOutcomeAssembler assembler,
                                  Function<TrialProgress, TrialProgress> liveOutlet,
                                  BooleanSupplier leafChoiceAllowed, RouteChooser chooser) {
        this.candidateLoader = candidateLoader;
        this.children = children;
        this.answers = answers;
        this.assembler = assembler;
        this.liveOutlet = liveOutlet;
        this.leafChoiceAllowed = leafChoiceAllowed;
        this.chooser = chooser;
    }

    /**
     * 执行 ROUTER 并行模板一轮：判路由 → ≥2 子并行 ReAct（或单子）→ 终答 CONCAT/SUMMARIZE/VOTE。
     *
     * @param parent       绑根的 ROUTER；虚拟根为合成的只读壳，可空
     * @param spec         方案执行来源；可空
     * @param userText     用户原文（判路由与兜底文案都从它出发）
     * @param modelUser    已注入记忆的用户侧文本（子执行用，判路由仍用原文）
     * @param progress     可空；子正文按身份增量出字
     * @param runtime      已解析的运行配置（模板 / 上限 / 汇总策略 / 多意图开关从这里读）
     * @param parentLimits 父墙钟与轮次上限
     * @param hint         线路提示（沿用 / 强制换线）；可空
     * @param rootId       编排根主键（旧 Agent 有，方案为 null）
     * @param rootName     编排根展示名（方案名 / 旧 Agent 名），多问分答要用
     * @return 路由说明、决策块、分段、说明与汇总后的终答
     * @throws TrialFailure 零可用候选 / 判路由墙钟超时 / 汇总不可用
     * @throws MaPauseSignal 子执行冒泡（原样上抛，不压成失败）
     */
    public RoutedTurn execute(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                              long sessionId, TrialProgress progress, AgentRuntimeConfig runtime,
                              EffectiveLimits parentLimits, RouteHint hint, Long rootId, String rootName) {
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, "judge", rootId);
        try {
            RouterClock.assertBefore(deadlineNanos);
            // 候选装载仍走第一波搬出的组件：过滤、去重、被挡原因都不在拓扑里重算
            Candidates loaded = candidateLoader.load(rootId, runtime, spec);
            List<Candidate> candidates = loaded.usable();
            List<BlockedCandidate> blockedCandidates = loaded.blocked();
            // 并行模板没有可用候选即整次失败：文案区分「一个都没有」与「全被编排策略挡下」
            if (candidates.isEmpty()) {
                throw new TrialFailure(blockedCandidates.isEmpty()
                        ? "Router 没有可用的子 Agent"
                        : "没有可用的子 Agent：" + blockedCandidates.size()
                                + " 位专家未获本编排允许（" + RouterTexts.blockedReasons(blockedCandidates)
                                + "），请检查编排策略与对应 Agent 配置");
            }
            // 判路由在 RouterJudge：这里只回调，单问兜底顺序由实现方保证
            RoutedChoice choice = chooser.choose(parent, spec, parentLimits, runtime, userText, candidates,
                    userId, sessionId, deadlineNanos, hint, progress);
            List<RoutedIntent> intents = choice.intents();
            // 关多意图：只保留第一项（强制线路恒为单项，已被 choose 收敛，不在此重复截断）。
            // 一条消息里的多个问题不受这个开关影响：那是「两问各回各家」，截断会直接丢问题。
            if (!choice.multiQuestion() && !runtime.multiIntentOn() && intents.size() > 1) {
                intents = List.of(intents.get(0));
            }
            // 手动换线 / 强制目标失效的可见说明：进 SYSTEM 事件，不打断正文。
            // 沿用线路与本轮选择不一致时不再写「已将线路改派」：入口句已经按本轮选择说出，再补一句就是改口。
            List<String> routeNotices = routeNotices(choice, intents);
            List<String> routeLines = new ArrayList<>();
            for (RoutedIntent intent : intents) {
                routeLines.add(RouterTexts.routeSummary(intent.candidate(), intent.reason()));
            }
            String decisionJson = RouteDecisionParser.toDecisionJson(intents.stream()
                    .map(item -> new RouteDecisionParser.Item(
                            item.candidate().route().getRouteKey() == null ? "" : item.candidate().route().getRouteKey(),
                            item.subQuery()))
                    .toList());
            // 多意图 / 多问：只推决策块。各子「路由到」在子开跑的 begin 时再推，位置由 begin 先后决定
            if (progress != null) {
                if (intents.size() >= 2) {
                    /*
                     * 决策行只告诉入口「拆成了几问、交给谁」。
                     * 不再在子任务开始前按工作流优先预推无身份的「路由到」：
                     * 那些行会先占块位，begin 到达后只能原位补身份，卡片就停在预推的位置上。
                     * 块序只认各自 begin 帧的 depth、kind 和先后。
                     */
                    progress.route(choice.multiQuestion()
                            ? RouterTexts.questionDecisionLine(intents)
                            : RouterTexts.decisionLine(intents));
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
            /*
             * 多问拆分的只读打点：只写「是否拆分 / 问题条数 / 去重目标数」三个计数，
             * 不写问题正文与目标名，也不改变分流结果。块序不靠这几个数重排。
             */
            RouterObs.questionAttrs(routeFields, choice.multiQuestion(), intents.size(),
                    RouterTexts.distinctLabels(intents).size());
            // 共享草稿只写键数 / 冲突次数（有则显示），不写任何 key / value 正文
            routeFields.putAll(RouterObs.scratchAttrs());
            route.ok(String.join("；", routeLines), decisionJson,
                    intents.size() == 1 ? intents.get(0).candidate().agent().getId() : null,
                    intents.size() <= 1 ? "路由到 " + RouterTexts.childLabel(intents.get(0).candidate().agent())
                            : "多意图路由×" + intents.size(),
                    routeFields);

            /*
             * 多子 / SUMMARIZE / VOTE：子侧正文**照样按身份增量出字**（见 childBodyOutlet）。
             * 旧实现把每个子的 assistant 整段静音、等该子结束才补一次，页面就是
             * 「泳道转很久 → 各子同时冒出整段」。前端早已按 participantId 分块，
             * 交错增量不会串块，所以这里不再压住正文。
             * 仍然要压的只有选项块：chips 必须挂在真正对用户说话的那一段下面。
             */
            boolean multiQuestion = choice.multiQuestion();
            boolean bodyDeferred = !multiQuestion && (intents.size() > 1
                    || runtime.answerModeEnum() == RouterAnswerMode.SUMMARIZE
                    // VOTE 的终答是投票结论，子正文只作为各自的作答展示
                    || runtime.answerModeEnum() == RouterAnswerMode.VOTE);
            // 路由列表≥2 强制隔离上下文；单意图继承父历史（兼容追问）
            boolean isolateChild = intents.size() >= 2;
            TrialProgress childProgress = liveOutlet.apply(progress);
            // 先链后扇：子 AGENT 挂判路由 LLM，禁止挂 ROUTE/根；无 LLM 时退 ROUTE
            Long childParent = ChildRunner.childAgentParent(trace, route.id());
            // 选项块只给「单专家」：多意图并行时每个子都抛一组选项会让用户无从拍板；
            // 汇总 / 投票时同样不开——chips 必须挂在真正对用户说话的那一段下面
            boolean leafChoice = !multiQuestion && intents.size() == 1 && !bodyDeferred
                    && leafChoiceAllowed.getAsBoolean();
            List<ChildOutcome> outcomes = children.runChildren(parent, intents, modelUser, userText, userId, sessionId,
                    deadlineNanos, childProgress, trace, childParent, isolateChild, leafChoice);
            // 路由侧说明先写：换线 / 改派要排在本轮子结果之前，时间线顺序才符合因果
            List<String> notices = new ArrayList<>(routeNotices);
            List<RouteSegment> segments = new ArrayList<>();
            assembler.collectRoutes(outcomes, routeLines, notices, segments);
            /*
             * 未允许被编排调用的子不参与判路由，但必须在时间线留「已跳过」：
             * 只写 routeLine + notice，不塞进 outcomes，避免改变终答拼接与流式推送口径。
             */
            assembler.appendBlocked(blockedCandidates, userText, notices, segments);
            AggregateOutcome aggregate = multiQuestion
                    ? answers.separateAnswers(rootName, outcomes, progress)
                    : answers.finishAnswer(parent, spec, parentLimits, runtime, userText, outcomes, intents,
                            userId, sessionId, deadlineNanos, progress, trace, route.id());
            // 投票结论也要进时间线：winner 一行 + 降级 / 跳过投票的中文说明
            assembler.applyAggregate(segments, notices, aggregate);
            TrialTurn turn = aggregate.turn();
            String decisionBlock = intents.size() >= 2
                    ? (multiQuestion ? RouterTexts.questionDecisionLine(intents) : RouterTexts.decisionLine(intents))
                    : null;
            // 单意图沿用本条线路；多意图清空（下一轮重判，避免把协作误降级成单人）
            RouteSticky sticky = assembler.stickyOf(intents);
            return new RoutedTurn(String.join("；", routeLines), decisionBlock, segments, notices, turn, sticky);
        } catch (RuntimeException ex) {
            // 判路由 / 子执行失败同样是关键 catch：带 Throwable 打全栈，并在 ROUTE 步骤写脱敏 cause。
            // 停等信号（MaPauseSignal）也走这里原样上抛：与拆前一致，不换成失败、不吞掉。
            log.warn("Router 执行失败 parentId={} type={} cause={}",
                    rootId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            route.error("路由失败", ex);
            throw ex;
        } finally {
            RouteSpan.unbind(trace);
        }
    }

    /**
     * 线路类可见说明：手动换线 / 强制目标失效。
     *
     * <p>只写用户能理解的中文与候选展示名，不暴露 route_key；
     * 这些说明会落成 SYSTEM 事件（思考区），不打断正文。
     * 沿用线路与本轮选择不同时不写「已将线路改派」：入口路由行已经是本轮的唯一分发，
     * 再补一条从旧线路指向新线路的说明，页面上就是说完又改口。
     *
     * @param choice  判路由结果
     * @param intents 最终执行的意图（已被多意图开关截断）
     * @return 中文说明；不需要说明时为空列表
     */
    private List<String> routeNotices(RoutedChoice choice, List<RoutedIntent> intents) {
        List<String> notices = new ArrayList<>();
        if (choice.forceMissed()) {
            notices.add("手动换线的目标线路已停用或不存在，本轮改由 Router 重新判定");
        }
        if (intents.isEmpty() || !choice.manual()) {
            return notices;
        }
        notices.add("手动换线：本轮按你选择的线路「" + RouterTexts.childLabel(intents.get(0).candidate().agent())
                + "」执行，未走判路由");
        return notices;
    }
}

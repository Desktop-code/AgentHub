package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.runtime.application.RouterTrialExecutor.RouteSegment;
import com.agenthub.runtime.application.RouterTrialExecutor.RoutedTurn;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.RouteDecisionParser;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 路由后串行拓扑（{@code ROUTER_THEN_SEQUENCE}）。所属域：runtime。
 *
 * <p>从 {@code RouterTrialExecutor.executeRouterThenSequence} 搬出：判路由调用、执行顺序排序、
 * 串行上限截断与跳过、串行执行、停等边界、ROUTE 收尾与汇总结论全部收在本类。
 * 执行器只保留 {@code executeTemplates} 的早分支分派，早分支顺序不变。
 *
 * <p>与判路由并行路径<b>共用同一份 JUDGE 契约</b>（同一份提示词与解析器），差别只在扇出方式：
 * 命中结果按候选的<b>执行顺序</b>串行接力；判定数组只决定选中了谁。
 * 判路由家族（{@code chooseOrdered} / 提示词装配 / 兜底规则）在 {@link RouterJudge}，本类只通过
 * {@link OrderedChooser} 回调调用，因此严格版「判路由失败即整次失败、不拿第一条候选顶上」的语义
 * 不会因为搬家而退化成并行模板那种兜底顺序。
 *
 * <p>关键纪律（逐字保持不变）：
 * <ul>
 *   <li>判路由不可省：父模型缺失 / 调用失败 / 无有效 route_key 一律整次 FAIL；</li>
 *   <li>后一步输入＝本步子问 + 前置终答摘要（{@code PRIOR_SUMMARY_ONLY} 为默认），
 *       摘要硬顶复用 {@link com.agenthub.runtime.domain.service.FixedSequenceContext}；</li>
 *   <li>子步跑在 {@link ChildRunner#runFixedSequence} 上，因此「部分失败继续」与 P&E 单槽冒泡自动继承；</li>
 *   <li>选中人数超过 {@code maxRouteChildren} 时只跑执行顺序前 N，其余标 SKIPPED_QUOTA；</li>
 *   <li>汇总复用 CONCAT / SUMMARIZE（{@link AnswerAggregator#finishAnswer}），不另造汇总器。</li>
 * </ul>
 *
 * <p>obs：ROUTE 名为 {@code router-then-sequence}，attrs 写模板 / 汇总 / 上下文三项，
 * <b>不写</b> {@code ma.skip_judge}——本模板确实调用了判路由 LLM。
 *
 * <p>明确不搬：
 * <ul>
 *   <li>子正文出口 {@code childBodyOutlet}：直播契约，仍由执行器持有，这里按注入的单参数版本使用；</li>
 *   <li>停等边界判定 {@code pauseScopeAllowed}：选项开关 / 嵌套深度 / 单链标记的既有口径，仍由执行器判定；</li>
 *   <li>根 id / 根名解析：沿用执行器，作为一次执行的现场传入，避免两处各算一份；</li>
 *   <li>{@link RoutedTurn} / {@link RouteSegment}：本刀按约定不搬 DTO。</li>
 * </ul>
 */
public final class RouterThenSequenceTopology {

    private static final Logger log = LoggerFactory.getLogger(RouterThenSequenceTopology.class);

    /** 候选装载：旧路由行 / 方案候选表的过滤与去重口径都在这里 */
    private final RouterCandidateLoader candidateLoader;
    /** 子 Agent 串行推进（复用固定串行的前置摘要与停等冒泡） */
    private final ChildRunner children;
    /** 终答汇总（CONCAT / SUMMARIZE / VOTE） */
    private final AnswerAggregator answers;
    /** 分段 / 说明 / 跳过 / blocked / 汇总的组装口径 */
    private final RouterOutcomeAssembler assembler;
    /** 子正文出口：执行器把 {@code childBodyOutlet(progress, true)} 包成单参数传进来（直播契约不搬） */
    private final Function<TrialProgress, TrialProgress> liveOutlet;
    /** 本轮是否允许串行成员中途停等；口径仍由执行器判定 */
    private final BooleanSupplier pauseAllowed;
    /** 严格版判路由：判路由家族仍留在执行器，这里只按接口调用 */
    private final OrderedChooser chooser;

    /**
     * 严格版判路由回调：把 {@link RouterJudge#chooseOrdered} 接到拓扑里，签名与拆前逐字一致。
     *
     * <p>实现方=RouterJudge；调用方=本拓扑。本类不做任何兜底或重判：实现方返回什么顺序、
     * 抛什么失败（含墙钟超时），这里原样接受并上抛。
     */
    @FunctionalInterface
    public interface OrderedChooser {

        /**
         * 判一次路由，返回「选中谁」的意图列表（顺序由实现方决定，本类随后按执行顺序重排）。
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
         * @param progress      可空思考出口
         * @return 命中的意图；非空
         * @throws TrialFailure 判路由不可用或墙钟超时（本模板不做兜底，原样整次失败）
         */
        List<RoutedIntent> chooseOrdered(AgentDef parent, MaRunSpec spec, EffectiveLimits parentLimits,
                                         AgentRuntimeConfig runtime, String userText,
                                         List<Candidate> candidates, long userId, long sessionId,
                                         long deadlineNanos, TrialProgress progress);
    }

    /**
     * @param candidateLoader 候选装载
     * @param children        子执行（串行推进）
     * @param answers         终答汇总
     * @param assembler       结果组装
     * @param liveOutlet      子正文直播出口（单参数包装）
     * @param pauseAllowed    是否允许串行停等
     * @param chooser         严格版判路由回调（实现方为执行器）
     */
    public RouterThenSequenceTopology(RouterCandidateLoader candidateLoader, ChildRunner children,
                                      AnswerAggregator answers, RouterOutcomeAssembler assembler,
                                      Function<TrialProgress, TrialProgress> liveOutlet,
                                      BooleanSupplier pauseAllowed, OrderedChooser chooser) {
        this.candidateLoader = candidateLoader;
        this.children = children;
        this.answers = answers;
        this.assembler = assembler;
        this.liveOutlet = liveOutlet;
        this.pauseAllowed = pauseAllowed;
        this.chooser = chooser;
    }

    /**
     * 执行路由后串行一轮：判路由 → 按执行顺序串行 → 终答 CONCAT/SUMMARIZE/VOTE。
     *
     * @param parent       已启用的 ROUTER
     * @param spec         方案执行来源；可空
     * @param userText     用户原问全文（判路由输入，也是各步摘要区的整体目标）
     * @param modelUser    已拼好记忆/近轮的用户侧文本（单步可继承父历史时使用）
     * @param progress     可空；子正文按身份增量出字
     * @param runtime      已解析的运行配置（模板、上下文策略、汇总、上限从这里读）
     * @param parentLimits 父墙钟与轮次上限
     * @param rootId       编排根主键（旧 Agent 有，方案为 null）
     * @param rootName     编排根展示名（方案名 / 旧 Agent 名），停等快照要用
     * @return 各步分段 + 汇总后的终答
     * @throws TrialFailure 零可用候选 / 判路由失败 / 全部子未完成 / 撞父墙钟
     * @throws MaPauseSignal 串行成员中途向用户提问（原样上抛，不压成失败）
     */
    public RoutedTurn execute(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                              long sessionId, TrialProgress progress, AgentRuntimeConfig runtime,
                              EffectiveLimits parentLimits, Long rootId, String rootName) {
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, "router-then-sequence", rootId);
        try {
            RouterClock.assertBefore(deadlineNanos);
            Candidates loaded = candidateLoader.load(rootId, runtime, spec);
            List<Candidate> candidates = loaded.usable();
            // M4：本模板先判路由再串行，判路由本身没有「降级兜底」，零可用候选必须整次 FAIL
            if (candidates.isEmpty()) {
                throw new TrialFailure(loaded.blocked().isEmpty()
                        ? "路由后串行没有可用的子 Agent：请至少启用 1 个获允许的子 Agent"
                        : "路由后串行没有可用的子 Agent：" + loaded.blocked().size()
                                + " 位专家未获本编排允许（" + RouterTexts.blockedReasons(loaded.blocked())
                                + "），请检查编排策略与对应 Agent 配置");
            }
            // 1) JUDGE：严格版判路由，失败即 FAIL，绝不拿第一条候选顶上。
            // 模型只决定选谁；真正的接力顺序用候选上的执行顺序，不用判定数组的先后
            List<RoutedIntent> judged = new ArrayList<>(chooser.chooseOrdered(parent, spec, parentLimits, runtime,
                    userText, candidates, userId, sessionId, deadlineNanos, progress));
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
                        RouterTexts.serialRouteLine("路由后串行 ", base.candidate(), i + 1, picked.size())));
            }
            List<RoutedIntent> skipped = new ArrayList<>();
            for (int i = cap; i < judged.size(); i++) {
                RoutedIntent leftover = judged.get(i);
                skipped.add(new RoutedIntent(leftover.candidate(), leftover.subQuery(), "超出串行上限",
                        "已跳过「" + RouterTexts.childLabel(leftover.candidate().agent()) + "」：超出串行上限 "
                                + cap));
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
            // 多子 / VOTE 同样按身份增量出字：投票结论只在收尾追加，不压住各路作答
            TrialProgress childProgress = liveOutlet.apply(progress);
            /*
             * 串行方案允许成员中途询问：条件与固定串行同一口径——
             * 工作台开启选项、读到真实方案，且这条链每一层都只派了一位。
             * 不满足时传 null，仍走「整轮末尾才出选项」的旧路径。
             */
            boolean serialLeafChoice = spec != null && pauseAllowed.getAsBoolean();
            TurnNotes pauseNotes = TurnNotes.current();
            MaPauseScope pauseScope = serialLeafChoice
                    ? MaPauseScope.root(spec.maId(), rootName, runtime.maTemplateOrDefault(),
                    MaNestedContext.depth(), MaNestedContext.parentKey(),
                    userText, modelUser, skipped.stream().map(ChildRunner::pauseMemberOfSkipped).toList(),
                    pauseNotes == null ? null : pauseNotes.gateway,
                    pauseNotes == null ? null : pauseNotes.judgeReasoning.toString(),
                    decisionJson)
                    : null;
            List<ChildOutcome> outcomes = children.runFixedSequence(parent, modelUser, userText, userId, sessionId,
                    deadlineNanos, childProgress, trace, ChildRunner.childAgentParent(trace, route.id()), intents,
                    runtime, pauseScope, List.of(), null, serialLeafChoice);
            // 所有执行步都没产出正文：整次 FAIL，不拿占位段假装成功
            if (!outcomes.isEmpty() && outcomes.stream().allMatch(outcome -> outcome.turn() == null)) {
                throw new TrialFailure("路由后串行全部子 Agent 均未完成，请检查子 Agent 配置或稍后重试");
            }
            List<ChildOutcome> all = new ArrayList<>(outcomes);
            assembler.appendSkippedOutcomes(skipped, "串行上限", cap, all);
            closeRoute(route, runtime, spec, all, decisionJson);
            List<String> notices = new ArrayList<>();
            List<RouteSegment> segments = new ArrayList<>();
            assembler.collect(all, notices, segments);
            /*
             * 未获允许的子不参与判路由，但必须在时间线留「已跳过」：
             * 只写 routeLine + notice，不塞进 all，避免改变终答口径。
             */
            assembler.appendBlocked(loaded.blocked(), userText, notices, segments);
            // 4) AGGREGATE：串行结束后再汇总，CONCAT / SUMMARIZE / VOTE 与现网同口径
            AggregateOutcome aggregate = answers.finishAnswer(parent, spec, parentLimits, runtime, userText, all,
                    intents, userId, sessionId, deadlineNanos, progress, trace, route.id());
            assembler.applyAggregate(segments, notices, aggregate);
            TrialTurn turn = aggregate.turn();
            List<String> lines = segments.stream().map(RouteSegment::routeLine).toList();
            return new RoutedTurn(String.join("；", lines), null, segments, notices, turn, null);
        } catch (MaPauseSignal pause) {
            // 停等不是失败：由入口落库，下一句回复时经 resumeSerial 回到同一成员后续跑
            route.ok("方案停等", null, null, "子专家等待用户回复", Map.of());
            throw pause;
        } catch (RuntimeException ex) {
            log.warn("路由后串行执行失败 parentId={} type={} cause={}",
                    rootId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            route.error("路由后串行失败", ex);
            throw ex;
        } finally {
            RouteSpan.unbind(trace);
        }
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
     * 关闭路由后串行的根 ROUTE：写实际模板 / 汇总策略 / 上下文策略与失败跳过计数。
     *
     * <p>与固定模板的关键差别：本模板<b>确实调用了</b>判路由 LLM，因此绝不写
     * {@code ma.skip_judge}；泳道据模板码区分「JUDGE 后串行」与「无判路由」。
     *
     * @param route        根 ROUTE
     * @param runtime      运行配置
     * @param spec         方案来源；可空
     * @param outcomes     本轮全部子结果（含跳过占位）
     * @param decisionJson 决策块 JSON
     */
    private void closeRoute(RouteSpan route, AgentRuntimeConfig runtime, MaRunSpec spec,
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
}

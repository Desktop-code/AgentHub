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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 固定模板拓扑（FIXED_PARALLEL / FIXED_SEQUENCE）。所属域：runtime。
 *
 * <p>从 {@code RouterTrialExecutor.executeFixed} 搬出：执行器只保留 `executeTemplates` 的早分支分派，
 * 固定模板自己的候选装载、并行上限、跳过、停等边界、执行、ROUTE 收尾与汇总全部收在本类。
 *
 * <p>与判路由路径的区别（逐字保持不变）：<b>完全跳过判路由 LLM</b>，参与者＝全部启用候选
 * （按 sort_order 升序，同序按 id），每个子的 sub_query 都是用户原问全文，不做意图切分。
 *
 * <p>观测：根 ROUTE 名称固定为 fixed-parallel / fixed-sequence；因为无判路由 LLM，
 * 子 AGENT 直接挂 ROUTE。并行子按同父扇出（N.1/N.2），串行子不扇出，泳道时间窗互不重叠。
 *
 * <p>明确不搬：
 * <ul>
 *   <li>子正文出口 {@code childBodyOutlet}：直播契约，仍由执行器持有，这里按注入的单参数版本使用；</li>
 *   <li>停等边界判定 {@code pauseScopeAllowed}：选项开关 / 嵌套深度 / 单链标记的既有口径，仍由执行器判定；</li>
 *   <li>根 id / 根名解析：沿用执行器，作为一次执行的现场传入，避免两处各算一份；</li>
 *   <li>{@link RoutedTurn} / {@link RouteSegment}：本刀按约定不搬 DTO。</li>
 * </ul>
 */
public final class FixedTopology {

    private static final Logger log = LoggerFactory.getLogger(FixedTopology.class);

    /** 候选装载：旧路由行 / 方案候选表的过滤与去重口径都在这里 */
    private final RouterCandidateLoader candidateLoader;
    /** 子 Agent 执行（并行扇出 / 串行推进 / 停等冒泡） */
    private final ChildRunner children;
    /** 终答汇总（CONCAT / SUMMARIZE / VOTE） */
    private final AnswerAggregator answers;
    /** 分段 / 说明 / 跳过 / blocked / 汇总的组装口径 */
    private final RouterOutcomeAssembler assembler;
    /** 子正文出口：执行器把 {@code childBodyOutlet(progress, true)} 包成单参数传进来（直播契约不搬） */
    private final Function<TrialProgress, TrialProgress> liveOutlet;
    /** 本轮是否允许串行成员中途停等；口径仍由执行器判定 */
    private final BooleanSupplier pauseAllowed;

    /**
     * @param candidateLoader 候选装载
     * @param children        子执行
     * @param answers         终答汇总
     * @param assembler       结果组装
     * @param liveOutlet      子正文直播出口（单参数包装）
     * @param pauseAllowed    是否允许串行停等
     */
    public FixedTopology(RouterCandidateLoader candidateLoader, ChildRunner children, AnswerAggregator answers,
                         RouterOutcomeAssembler assembler, Function<TrialProgress, TrialProgress> liveOutlet,
                         BooleanSupplier pauseAllowed) {
        this.candidateLoader = candidateLoader;
        this.children = children;
        this.answers = answers;
        this.assembler = assembler;
        this.liveOutlet = liveOutlet;
        this.pauseAllowed = pauseAllowed;
    }

    /**
     * 执行固定模板一轮。
     *
     * @param parent       旧 Agent 试跑的 ROUTER；方案运行可为 null
     * @param spec         方案执行来源；可空
     * @param userText     用户原文（所有子共用，不做切分）
     * @param modelUser    已注入记忆的用户侧文本（串行时用于继承父历史的分支）
     * @param progress     可空；子正文按身份增量出字
     * @param runtime      已解析的运行配置（模板 / 上限 / 上下文策略 / 汇总策略从这里读）
     * @param parentLimits 父墙钟与轮次上限
     * @param rootId       编排根主键（旧 Agent 有，方案为 null）
     * @param rootName     编排根展示名（方案名 / 旧 Agent 名），停等快照要用
     * @return 路由说明、分段与汇总后的终答
     * @throws TrialFailure 零可用候选 / 串行全部未完成 / 撞父墙钟
     * @throws MaPauseSignal 串行成员中途向用户提问（原样上抛，不压成失败）
     */
    public RoutedTurn execute(AgentDef parent, MaRunSpec spec, String userText, String modelUser, long userId,
                              long sessionId, TrialProgress progress, AgentRuntimeConfig runtime,
                              EffectiveLimits parentLimits, Long rootId, String rootName) {
        boolean parallel = runtime.isFixedParallel();
        String template = runtime.maTemplateOrDefault();
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, parallel ? "fixed-parallel" : "fixed-sequence", rootId);
        try {
            RouterClock.assertBefore(deadlineNanos);
            Candidates loaded = candidateLoader.load(rootId, runtime, spec);
            List<Candidate> enabled = loaded.usable();
            List<BlockedCandidate> blockedCandidates = loaded.blocked();
            // M4 精神：固定模板没有判路由兜底，零可用候选必须整次 FAIL，不能空跑出「无实质回答」
            if (enabled.isEmpty()) {
                String suffix = blockedCandidates.isEmpty() ? ""
                        : "（其中 " + blockedCandidates.size() + " 位专家未获本编排允许："
                                + RouterTexts.blockedReasons(blockedCandidates) + "）";
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
                        RouterTexts.serialRouteLine(parallel ? "固定并行 " : "固定串行 ", picked.get(i),
                                i + 1, picked.size())));
            }
            List<RoutedIntent> skipped = new ArrayList<>();
            if (parallel && enabled.size() > cap) {
                for (int i = cap; i < enabled.size(); i++) {
                    Candidate leftover = enabled.get(i);
                    skipped.add(new RoutedIntent(leftover, userText, "超出并行上限",
                            "已跳过「" + RouterTexts.childLabel(leftover.agent()) + "」：超出并行上限 " + cap));
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
            // 多子或 SUMMARIZE / VOTE：正文仍按身份增量出字，等齐后再由汇总那一步收口终答
            TrialProgress childProgress = liveOutlet.apply(progress);
            /*
             * 串行方案允许子专家中途询问：工作台开启选项、读到真实方案（spec），
             * 且这条链上每一层都只派了一位（根层仍是唯一入口，嵌套层要求 SoleChainHolder 为真）。
             * 并行 / 多成员串行 / 未开放停等的链传 null，行为与现网逐字一致。
             */
            boolean serialLeafChoice = !parallel && spec != null && pauseAllowed.getAsBoolean();
            TurnNotes pauseNotes = TurnNotes.current();
            MaPauseScope pauseScope = serialLeafChoice
                    ? MaPauseScope.root(spec.maId(), rootName, template,
                    MaNestedContext.depth(), MaNestedContext.parentKey(), userText, modelUser,
                    skipped.stream().map(ChildRunner::pauseMemberOfSkipped).toList(),
                    pauseNotes == null ? null : pauseNotes.gateway,
                    pauseNotes == null ? null : pauseNotes.judgeReasoning.toString(),
                    decisionJson)
                    : null;
            List<ChildOutcome> outcomes = parallel
                    ? children.runChildren(parent, intents, modelUser, userText, userId, sessionId, deadlineNanos,
                    childProgress, trace, route.id(),
                    // 多子强制隔离；单子按 maAllowParentHistoryWhenSingle 决定是否继承父历史
                    intents.size() >= 2 || !runtime.allowParentHistoryWhenSingleOn())
                    : children.runFixedSequence(parent, modelUser, userText, userId, sessionId, deadlineNanos,
                    childProgress, trace, route.id(), intents, runtime, pauseScope, List.of(), null,
                    serialLeafChoice);
            // 串行全失败：与单子失败口径一致，整次 FAIL，不拿占位句伪装成功
            if (!parallel && !outcomes.isEmpty()
                    && outcomes.stream().allMatch(outcome -> outcome.turn() == null)) {
                throw new TrialFailure("固定串行全部子 Agent 均未完成，请检查子 Agent 配置或稍后重试");
            }
            List<ChildOutcome> all = new ArrayList<>(outcomes);
            // 超出上限的候选仍要进汇总与事件，让用户看到「为什么它没跑」
            assembler.appendSkippedOutcomes(skipped, "并行上限", cap, all);
            closeRoute(route, runtime, spec, template, all, decisionJson, parallel);
            List<String> notices = new ArrayList<>();
            List<RouteSegment> segments = new ArrayList<>();
            assembler.collect(all, notices, segments);
            /*
             * 未允许被编排调用的子：只追加路由行 + notice，不进 all，
             * 因此不参与终答拼接、不计 token，也不会触发额外的 assistant 推送。
             */
            assembler.appendBlocked(blockedCandidates, userText, notices, segments);
            AggregateOutcome aggregate = answers.finishAnswer(parent, spec, parentLimits, runtime, userText, all,
                    intents, userId, sessionId, deadlineNanos, progress, trace, route.id());
            // 投票结论进时间线 + SYSTEM：winner 一行、降级 / 跳过投票说明
            assembler.applyAggregate(segments, notices, aggregate);
            TrialTurn turn = aggregate.turn();
            List<String> lines = segments.stream().map(RouteSegment::routeLine).toList();
            return new RoutedTurn(String.join("；", lines), null, segments, notices, turn, null);
        } catch (MaPauseSignal pause) {
            // 停等是正常控制流：不写失败日志、不把 ROUTE 标成 ERROR，由入口落库并在下一句续跑
            route.ok("方案停等", null, null, "子专家等待用户回复", Map.of());
            throw pause;
        } catch (RuntimeException ex) {
            log.warn("固定编排执行失败 parentId={} template={} type={} cause={}",
                    rootId, template, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            route.error(parallel ? "固定并行失败" : "固定串行失败", ex);
            throw ex;
        } finally {
            RouteSpan.unbind(trace);
        }
    }

    /**
     * 固定模板的总控说明：让用户一眼看到「跳过判路由」以及被跳过的原因。
     *
     * @param parallel 是否固定并行
     * @param picked   实际执行子数
     * @param skipped  超限跳过子数
     * @param cap      并行上限
     * @return 中文说明
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
     * 关闭固定模板的根 ROUTE：写实际模板 / 汇总策略 / 上下文策略，并附跳过与失败计数。
     *
     * @param route       根 ROUTE
     * @param runtime     运行配置
     * @param spec        方案来源；可空
     * @param template    实际模板码
     * @param outcomes    本轮全部子结果（含跳过占位）
     * @param decisionJson 决策块 JSON；固定模板无判路由，仍按参与者 route_key 写
     * @param parallel    是否并行
     */
    private void closeRoute(RouteSpan route, AgentRuntimeConfig runtime, MaRunSpec spec, String template,
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
}

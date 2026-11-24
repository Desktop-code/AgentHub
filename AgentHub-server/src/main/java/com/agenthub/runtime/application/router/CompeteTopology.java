package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.runtime.application.RouterTrialExecutor.RouteSegment;
import com.agenthub.runtime.application.RouterTrialExecutor.RoutedTurn;
import com.agenthub.runtime.application.SystemPromptAssembler;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.CompeteVerdictParser;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.RouteDecisionParser;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 竞争模板拓扑（{@code COMPETE}）。所属域：runtime。
 *
 * <p>从 {@code RouterTrialExecutor.executeCompete} 搬出：候选装载、同题并行、选优（策略 / 评委调用 /
 * 降级）、全失败、跳过、分段与 ROUTE 收尾全部收在本类。执行器只保留 {@code executeTemplates}
 * 的早分支分派，早分支顺序不变：评审环 → 竞争 → 固定模板 → 路由后串行 → 路由并行。
 *
 * <p>拓扑固定为 <b>同题并行 → 选优 → 终答</b>：
 * <ul>
 *   <li>跳过判路由 LLM：每个启用候选都拿<b>同一份用户原问</b>作答，不做意图切分；</li>
 *   <li>并行纪律与 {@code FIXED_PARALLEL} 完全一致：复用 {@link ChildRunner#runChildren}，超
 *       {@code maxRouteChildren} 的候选按序标 SKIPPED_QUOTA，单子失败不阻其它子；</li>
 *   <li>选优策略由 {@code competeStrategy} 决定：
 *       {@code JUDGE_PICK}（默认，评选模型选 1 个赢家）/{@code FIRST_SUCCESS}（第一个成功子）；</li>
 *   <li>终答只取赢家正文，不做多段拼接；token 与 traces 仍汇总全部子（每个子都真实消耗了额度）。</li>
 * </ul>
 *
 * <p>降级（逐字保持不变）：评选模型缺失 / 调用失败（非超时）/ 给出非法赢家时，
 * 只要还有成功子就降级 {@code FIRST_SUCCESS}，并在时间线写 SYSTEM 说明；
 * 全部子失败才整次 FAIL。超时仍按统一口径 FAIL，不把墙钟耗尽伪装成成功。
 *
 * <p>明确不搬：
 * <ul>
 *   <li>判路由家族（{@code judgeReply} / {@code choose} / {@code chooseOrdered} / {@code chooseForQuestions}）：
 *       在 {@link RouterJudge}；本模板本身不判路由，也不新增判路由；</li>
 *   <li>子正文出口 {@code childBodyOutlet}：直播契约，仍由执行器持有，这里按注入的单参数版本使用；</li>
 *   <li>根 id / 根名解析：沿用执行器，作为一次执行的现场传入，避免两处各算一份；</li>
 *   <li>{@link RoutedTurn} / {@link RouteSegment}：本刀按约定不搬 DTO。</li>
 * </ul>
 */
public final class CompeteTopology {

    private static final Logger log = LoggerFactory.getLogger(CompeteTopology.class);

    /** 竞争选优给评委看的单个子终答长度硬顶：超过就截断，避免一次评选吃掉父墙钟 */
    private static final int COMPETE_ANSWER_LIMIT = 3000;

    /** 候选装载：旧路由行 / 方案候选表的过滤与去重口径都在这里 */
    private final RouterCandidateLoader candidateLoader;
    /** 子 Agent 同题并行执行 */
    private final ChildRunner children;
    /** 终答合并（token / traces 汇总，正文取赢家）与评选模型解析 */
    private final AnswerAggregator answers;
    /** 分段 / 说明 / 跳过 / blocked 的组装口径 */
    private final RouterOutcomeAssembler assembler;
    /** 子正文出口：执行器把 {@code childBodyOutlet(progress, true)} 包成单参数传进来（直播契约不搬） */
    private final Function<TrialProgress, TrialProgress> liveOutlet;
    /** 评选模型调用：与判路由 / 汇总共用同一个单次模型入口 */
    private final ReActTrialExecutor react;
    /** 评委提示词的平台前缀：沿用 ROUTER 用途模板，避免多套前缀并存 */
    private final SystemPromptAssembler prompts;

    /**
     * @param candidateLoader 候选装载
     * @param children        子执行（同题并行）
     * @param answers         终答合并与评选模型解析
     * @param assembler       结果组装
     * @param liveOutlet      子正文直播出口（单参数包装）
     * @param react           评选模型调用入口
     * @param prompts         评委提示词的平台前缀装配
     */
    public CompeteTopology(RouterCandidateLoader candidateLoader, ChildRunner children, AnswerAggregator answers,
                           RouterOutcomeAssembler assembler, Function<TrialProgress, TrialProgress> liveOutlet,
                           ReActTrialExecutor react, SystemPromptAssembler prompts) {
        this.candidateLoader = candidateLoader;
        this.children = children;
        this.answers = answers;
        this.assembler = assembler;
        this.liveOutlet = liveOutlet;
        this.react = react;
        this.prompts = prompts;
    }

    /**
     * 执行竞争模板一轮：同题并行 → 选优 → 终答取赢家正文。
     *
     * <p>执行器只传本轮现场（根 id / 根名 / 墙钟），模板自身的候选范围、并行上限、跳过与
     * 降级口径全部在本类内完成，与拆前逐字一致。
     *
     * @param parent       绑根的 ROUTER；虚拟根为合成的只读壳
     * @param spec         方案执行来源；可空
     * @param userText     用户原文（所有候选共用）
     * @param modelUser    已注入记忆的用户侧文本
     * @param progress     可空；子正文按身份增量出字，收尾不重复贴赢家整段
     * @param runtime      已解析的运行配置（模板、策略、并行上限从这里读）
     * @param parentLimits 父墙钟与轮次上限
     * @param rootId       编排根主键（旧 Agent 有，方案为 null）
     * @param rootName     编排根展示名（方案名 / 旧 Agent 名），评委提示词要用
     * @return 终答＝赢家正文；segments 保留每个候选的标题与选优结论
     * @throws TrialFailure 可用候选不足 / 并行上限配置冲突 / 全部子失败 / 撞父墙钟
     */
    public RoutedTurn execute(AgentDef parent, MaRunSpec spec, String userText, String modelUser,
                              long userId, long sessionId, TrialProgress progress,
                              AgentRuntimeConfig runtime, EffectiveLimits parentLimits,
                              Long rootId, String rootName) {
        String strategy = runtime.competeStrategyOrDefault();
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, "compete", rootId);
        try {
            RouterClock.assertBefore(deadlineNanos);
            Candidates loaded = candidateLoader.load(rootId, runtime, spec);
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
                        "已跳过「" + RouterTexts.childLabel(leftover.agent()) + "」：超出竞争并行上限 " + cap));
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
            // 参赛者各按身份增量出字；收尾只推赢家那条时不把同一段正文再贴一次
            TrialProgress childProgress = liveOutlet.apply(progress);
            List<ChildOutcome> outcomes = children.runChildren(parent, intents, modelUser, userText, userId, sessionId,
                    deadlineNanos, childProgress, trace, route.id(),
                    // 同题竞争必须隔离：子只看用户原问，互相不可见，保证比较公平
                    true);
            List<ChildOutcome> successes = new ArrayList<>();
            for (ChildOutcome outcome : outcomes) {
                if (ChildOutcome.hasAnswer(outcome)) {
                    successes.add(outcome);
                }
            }
            // 全部子失败：不拿占位段伪装成功
            if (successes.isEmpty()) {
                throw new TrialFailure("竞争选优的全部子 Agent 均未完成，请检查子 Agent 配置或稍后重试");
            }
            CompetePick pick = pickCompeteWinner(parent, spec, strategy, userText, successes, userId, sessionId,
                    deadlineNanos, parentLimits, progress, trace, route.id(), rootId, rootName);
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
            assembler.collectPicked(outcomes, pick.outcome(), notices, segments);
            assembler.appendSkippedLines(skipped, segments);
            /*
             * 未允许被编排调用的子：只追加路由行 + notice，不进 outcomes，
             * 因此不参与选优、不计 token，也不会触发额外的 assistant 推送。
             */
            assembler.appendBlocked(blockedCandidates, userText, notices, segments);
            // 选优结论单独一行（含策略 / winner / 理由），时间线与泳道都能一眼看到为什么是它
            segments.add(new RouteSegment(pick.routeLine(), null, null));
            List<String> lines = segments.stream().map(RouteSegment::routeLine).toList();
            return new RoutedTurn(String.join("；", lines), null, segments, notices, turn, null);
        } catch (RuntimeException ex) {
            log.warn("竞争选优执行失败 parentId={} type={} cause={}",
                    rootId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
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
     * @param rootId        编排根主键（计量归属）
     * @param rootName      编排根展示名（评委提示词）
     * @return 赢家 + 策略 + 说明；degraded=true 表示发生过降级
     */
    private CompetePick pickCompeteWinner(AgentDef parent, MaRunSpec spec, String strategy, String userText,
                                          List<ChildOutcome> successes, long userId, long sessionId,
                                          long deadlineNanos, EffectiveLimits parentLimits, TrialProgress progress,
                                          ObsTraceScope trace, Long routeSpan, Long rootId, String rootName) {
        ChildOutcome first = successes.get(0);
        if (AgentRuntimeConfig.COMPETE_STRATEGY_FIRST_SUCCESS.equals(strategy)) {
            return new CompetePick(first, AgentRuntimeConfig.COMPETE_STRATEGY_FIRST_SUCCESS,
                    "按策略「首个成功子」直接出终答", false,
                    "选优：策略 FIRST_SUCCESS，winner=" + ChildOutcome.keyOf(first));
        }
        Optional<Long> judgeModel = answers.resolveRouterModel(parent, spec);
        if (judgeModel.isEmpty()) {
            return degradeToFirst(first, "未配置评选模型（maJudgeModelInstanceId / maJudgeModel），已按「首个成功子」出终答");
        }
        try {
            RouterClock.assertBefore(deadlineNanos);
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
                    MeterScope.console(rootId, judgeModel.get(), sessionId, userId),
                    competePrompt(rootName, userText, successes), competeUserText(userText, successes), judgeLimits);
            Optional<CompeteVerdictParser.Verdict> verdict = CompeteVerdictParser.parse(reply,
                    successes.stream().map(item -> ChildOutcome.keyOf(item)).toList());
            if (verdict.isEmpty()) {
                return degradeToFirst(first, "评选模型未给出合法赢家（" + ChildOutcome.keyOf(first) + " 兜底）");
            }
            String winnerKey = verdict.get().winnerKey();
            ChildOutcome winner = successes.stream()
                    .filter(item -> winnerKey.equalsIgnoreCase(ChildOutcome.keyOf(item)))
                    .findFirst()
                    .orElse(first);
            String reason = verdict.get().reason().isBlank() ? "评选模型未给出理由" : verdict.get().reason();
            return new CompetePick(winner, AgentRuntimeConfig.COMPETE_STRATEGY_JUDGE_PICK, reason, false,
                    "选优：策略 JUDGE_PICK，winner=" + ChildOutcome.keyOf(winner) + "，理由：" + reason);
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
                "选优降级：" + note + "（winner=" + ChildOutcome.keyOf(first) + "）");
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
            prompt.append("- ").append(ChildOutcome.keyOf(outcome));
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
            text.append("### [").append(ChildOutcome.keyOf(outcome)).append("] ")
                    .append(RouterTexts.sectionTitle(outcome)).append('\n');
            text.append(TrialTexts.clip(outcome.turn().getAssistantText(), COMPETE_ANSWER_LIMIT)).append("\n\n");
        }
        return text.toString();
    }

    /**
     * 选优子块标题：与固定模板区分，明确「同题作答」。
     */
    private String competeRouteLine(Candidate hit, int index, int total) {
        return RouterTexts.serialRouteLine("竞争选优 ", hit, index, total) + " · 同题作答";
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
                .append(" → ").append(ChildOutcome.keyOf(pick.outcome()));
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
        fields.put("ma.compete.winner", ChildOutcome.keyOf(pick.outcome()));
        fields.put("ma.compete.degraded", pick.degraded() ? "true" : "false");
        fields.put("ma.compete.ok", String.valueOf(ok));
        fields.put("ma.compete.fail", String.valueOf(fail));
        // 绑根没有合成根步骤：方案标识补在这里，按 ma.def_id 检索时不会漏
        fields.putAll(RouterObs.maAttrs(spec));
        fields.putAll(RouterObs.scratchAttrs());
        route.ok("竞争选优完成：winner=" + ChildOutcome.keyOf(pick.outcome()), decisionJson, null,
                brief.toString(), fields);
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
                    .append(RouterTexts.blockedReasons(blocked)).append("）");
        }
        text.append("：请启用并授权更多候选专家");
        return text.toString();
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
}

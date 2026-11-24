package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.service.AgentCriticPolicy;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.runtime.application.RouterTrialExecutor.RouteSegment;
import com.agenthub.runtime.application.RouterTrialExecutor.RoutedTurn;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.CriticPrompt;
import com.agenthub.runtime.domain.service.CriticVerdictParser;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 评审环拓扑（{@code CRITIC_LOOP}）。所属域：runtime。
 *
 * <p>从 {@code RouterTrialExecutor.executeCriticLoop} 搬出：执行者 / 评审者选择、轮次推进、
 * 评审判定解析、失败降级、阶段分段与 ROUTE 收尾全部收在本类。执行器只保留
 * {@code executeTemplates} 的早分支分派，早分支顺序不变。
 *
 * <p>拓扑固定为 <b>执行者 → 评审者 →（REVISE 时）执行者回修</b>，全部串行：
 * <ul>
 *   <li>跳过判路由 LLM（不写 ROUTE.route_decision），与固定模板同一口径；</li>
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
 * <p>明确不搬：
 * <ul>
 *   <li>子正文出口 {@code childBodyOutlet}：直播契约，仍由执行器持有；本类按注入的
 *       「静音版」单参数包装使用（评审环所有阶段都不向直播推正文）；</li>
 *   <li>根 id 解析：沿用执行器，作为一次执行的现场传入；</li>
 *   <li>{@link RoutedTurn} / {@link RouteSegment}：本刀按约定不搬 DTO。</li>
 * </ul>
 */
public final class CriticLoopTopology {

    private static final Logger log = LoggerFactory.getLogger(CriticLoopTopology.class);

    /** 候选装载：旧路由行 / 方案候选表的过滤与去重口径都在这里 */
    private final RouterCandidateLoader candidateLoader;
    /** 子 Agent 执行：评审环三个阶段都以单子串行方式跑 */
    private final ChildRunner children;
    /** 终答合并（token / traces 汇总，正文取最后一版执行稿） */
    private final AnswerAggregator answers;
    /** 分段 / 说明 / blocked 的组装口径 */
    private final RouterOutcomeAssembler assembler;
    /** 子正文出口：执行器把静音版 {@code childBodyOutlet(progress, false)} 包成单参数传进来 */
    private final Function<TrialProgress, TrialProgress> stageOutlet;
    /** 评审者定义读取：评审专家不必出现在候选表，需按 id 直接取 AgentDef */
    private final AgentDefRepository agents;

    /**
     * @param candidateLoader 候选装载
     * @param children        子执行（单子串行）
     * @param answers         终答合并
     * @param assembler       结果组装
     * @param stageOutlet     子正文直播出口（静音版单参数包装）
     * @param agents          评审者定义读取
     */
    public CriticLoopTopology(RouterCandidateLoader candidateLoader, ChildRunner children,
                              AnswerAggregator answers, RouterOutcomeAssembler assembler,
                              Function<TrialProgress, TrialProgress> stageOutlet, AgentDefRepository agents) {
        this.candidateLoader = candidateLoader;
        this.children = children;
        this.answers = answers;
        this.assembler = assembler;
        this.stageOutlet = stageOutlet;
        this.agents = agents;
    }

    /**
     * 执行评审环一轮：执行者出稿 → 评审（可多轮）→ 必要时回修 → 终答取最后一版执行稿。
     *
     * @param parent       已启用的 ROUTER
     * @param spec         方案执行来源；可空
     * @param userText     用户原问全文；本模板恒按隔离上下文派发（不继承父历史）
     * @param userId       平台用户
     * @param sessionId    当前会话
     * @param progress     可空；评审/回修阶段的 assistant 增量被静音，终答等齐后只推一次
     * @param runtime      已解析的运行配置（模板、评审者、轮次从这里读）
     * @param parentLimits 父墙钟与轮次上限
     * @param rootId       编排根主键（旧 Agent 有，方案为 null）
     * @return 终答＝最后一版执行者输出，segments 保留每阶段标题与真实 spans
     * @throws TrialFailure 候选不合法 / 执行者未产出稿件 / 撞父墙钟
     */
    public RoutedTurn execute(AgentDef parent, MaRunSpec spec, String userText, long userId,
                              long sessionId, TrialProgress progress, AgentRuntimeConfig runtime,
                              EffectiveLimits parentLimits, Long rootId) {
        long deadlineNanos = System.nanoTime() + Math.max(parentLimits.timeoutMs(), 1000L) * 1_000_000L;
        ObsTraceScope trace = ObsTraceHolder.current();
        RouteSpan route = RouteSpan.open(trace, "critic-loop", rootId);
        // 评审环的所有阶段都静音 assistant：初稿若直接流出，REVISE 后会变成「两版答复」拼在时间线上
        // 评审环不按参与者分块交结论：仍只在收尾推一版执行稿，避免和终稿叠成两段
        TrialProgress stageProgress = stageOutlet.apply(progress);
        List<RouteSegment> segments = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        List<ChildOutcome> stages = new ArrayList<>();
        int maxRounds = runtime.criticMaxRoundsOrDefault();
        int roundsUsed = 0;
        String decision = CriticVerdictParser.DECISION_ACCEPT;
        boolean parseFailed = false;
        String degradeNote = null;
        try {
            RouterClock.assertBefore(deadlineNanos);
            Candidates loaded = candidateLoader.load(rootId, runtime, spec);
            Long criticId = runtime.getMaCriticAgentId();
            Candidate executor = pickCriticExecutor(loaded, criticId);
            AgentDef criticAgent = pickCriticAgent(runtime, criticId, executor);
            Candidate critic = new Candidate(criticRoute(criticAgent), criticAgent);

            String planLine = "评审环：跳过判路由，执行者出稿 → 评审最多 " + maxRounds + " 轮（不满意则回修）";
            pushLine(progress, segments, planLine);

            // 初稿：用户原问全文直发执行者；隔离上下文，单子也不继承父历史
            String draftLine = "评审环 · 执行者出稿";
            pushLine(progress, segments, draftLine);
            ChildOutcome draft = runStage(parent, executor, "评审环 · 执行者出稿", userText, userText,
                    userId, sessionId, deadlineNanos, stageProgress, trace, route.id(), "executor", 0);
            segments.add(new RouteSegment(draftLine, draft.turn(), null));
            notices.addAll(draft.notices());
            stages.add(draft);
            TrialTurn current = draft.turn();
            if (current == null) {
                throw new TrialFailure("评审环执行者未产出稿件，无法评审");
            }

            for (int round = 1; round <= maxRounds; round++) {
                RouterClock.assertBefore(deadlineNanos);
                String reviewLine = "评审环 · 评审（第 " + round + " 轮）";
                pushLine(progress, segments, reviewLine);
                String reviewInput = CriticPrompt.review(userText, current.getAssistantText());
                ChildOutcome reviewed;
                try {
                    reviewed = runStage(parent, critic, "评审环 · 评审", reviewInput, reviewInput,
                            userId, sessionId, deadlineNanos, stageProgress, trace, route.id(), "critic", round);
                } catch (TrialFailure ex) {
                    // 墙钟/根取消属于整次失败，不做降级；其它评审失败采用当前稿
                    if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                        throw ex;
                    }
                    log.warn("评审环评审失败 parentId={} round={} type={} cause={}",
                            rootId, round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    degradeNote = "评审未完成，已返回执行稿";
                    segments.add(new RouteSegment(reviewLine + " · 未完成", null, null));
                    break;
                } catch (RuntimeException ex) {
                    log.warn("评审环评审异常 parentId={} round={} type={} cause={}",
                            rootId, round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
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
                    revised = runStage(parent, executor, "评审环 · 回修", reviseInput, reviseInput,
                            userId, sessionId, deadlineNanos, stageProgress, trace, route.id(), "executor", round);
                } catch (TrialFailure ex) {
                    if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                        throw ex;
                    }
                    log.warn("评审环回修失败 parentId={} round={} type={} cause={}",
                            rootId, round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    degradeNote = "回修未完成，已返回上一版执行稿";
                    segments.add(new RouteSegment(reviseLine + " · 未完成", null, null));
                    break;
                } catch (RuntimeException ex) {
                    log.warn("评审环回修异常 parentId={} round={} type={} cause={}",
                            rootId, round, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
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
            closeRoute(route, runtime, spec, roundsUsed, decision, parseFailed);
            /*
             * 未获允许/被挡下的候选：只追加「已跳过」路由行，不进 stages，
             * 因此不参与终答、不计 token，也不会触发额外 assistant 推送。
             */
            assembler.appendBlocked(loaded.blocked(), userText, notices, segments);
            String summary = "评审环：" + roundsUsed + " 轮 · " + criticDecisionText(decision);
            return new RoutedTurn(summary, null, segments, notices, turn, null);
        } catch (RuntimeException ex) {
            log.warn("评审环执行失败 parentId={} type={} cause={}",
                    rootId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
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
                    : "（其中 " + loaded.blocked().size() + " 位专家未获本编排允许："
                            + RouterTexts.blockedReasons(loaded.blocked()) + "）";
            throw new TrialFailure("评审环需要恰好 1 个启用中的执行者候选，当前 0 个" + suffix
                    + "：候选需已启用、运行模式获本编排允许且已开启「允许被编排调用」");
        }
        if (executors.size() > 1) {
            List<String> names = executors.stream().map(item -> RouterTexts.childLabel(item.agent())).toList();
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
     * @param parent   编排根
     * @param hit      本阶段子候选（执行者或评审者）
     * @param roleText 角色：执行者出稿 / 评审 / 回修；用于路由行展示
     * @param prompt   本次送给子 Agent 的用户侧全文
     * @param subQuery span 展示用的子问（与 prompt 同源，避免把评审契约当成用户问题展示）
     * @param role     角色码：executor / critic；只用于 obs attrs 与泳道展示
     * @param round    轮次；初稿为 0
     * @return 子执行结果
     */
    private ChildOutcome runStage(AgentDef parent, Candidate hit, String roleText, String prompt,
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
     *
     * @param progress 实时出口；可空
     * @param segments 落库分段
     * @param line     路由行文案
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
     * 评审结论的中文展示。
     *
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
     *
     * @param route      根 ROUTE
     * @param runtime    运行配置
     * @param spec       方案来源；可空
     * @param roundsUsed 实际评审轮次
     * @param decision   最终判定码
     * @param parseFailed 是否出现过判定解析失败
     */
    private void closeRoute(RouteSpan route, AgentRuntimeConfig runtime, MaRunSpec spec, int roundsUsed,
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
}

package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.runtime.application.RouterTrialExecutor.RoutedChoice;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.RouteHint;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.RouteDecisionParser;
import com.agenthub.runtime.domain.service.RouteKeywordPicker;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 判路由决策（单问 / 多问 / 入口接待偏好 / 严格排序）。所属域：runtime。
 *
 * <p>从 {@code RouterTrialExecutor} 搬出：执行器只保留模板早分支、直播出口、停等边界与根解析，
 * 「判给谁」这一族决策集中在本类。提示词仍由 {@link RouterJudgePromptFactory} 装配，
 * 本类不重写、也不再搬第二套提示词。
 *
 * <p>口径（搬前搬后一致，禁止在这里改行为）：
 * <ul>
 *   <li>单问 {@code ROUTER_PARALLEL} 回落顺序不变：点名 → 关键字 → 职责覆盖 → 模型 → 第一条兜底；</li>
 *   <li>{@code ROUTER_THEN_SEQUENCE} 走 {@link #chooseOrdered}，<b>无兜底</b>：判路由失败即整次失败，
 *       不拿第一条候选顶上；</li>
 *   <li>多问拆分逐问选目标，入口点名 / 职责覆盖 / 手动换线 / 强制目标 / 粘性线路的现网语义不变；</li>
 *   <li>强制线路失效只记 {@code forceMissed}，由上层写可见说明，本类不伪造命中。</li>
 * </ul>
 *
 * <p>线程：本类无状态，只读协作对象；本轮上下文（{@link TurnNotes}、嵌套深度等）照旧从 ThreadLocal 读，
 * 进入 / 退出仍由执行器成对管理，本类不改写。
 */
public class RouterJudge {

    private static final Logger log = LoggerFactory.getLogger(RouterJudge.class);

    /** 入口方案成员的名称 / 简介查询：职责覆盖判定只读这里，不写库 */
    private final AgentDefRepository agents;
    /** 判定模型调用：与执行器共用同一份 ReAct 适配，不另建模型开户 */
    private final ReActTrialExecutor react;
    /** 判路由提示词装配：逐字口径与用户槽替换都在工厂里 */
    private final RouterJudgePromptFactory judgePrompts;
    /** 判路由模型解析：方案优先，旧 Agent 试跑才回落 Agent 模型槽 */
    private final AnswerAggregator answers;

    /**
     * @param agents       入口方案成员的名称 / 简介查询（职责覆盖判定用）
     * @param react        判定模型调用（与执行器共用同一份 ReAct 适配）
     * @param judgePrompts 判路由提示词装配（逐字口径在工厂里）
     * @param answers      判路由模型解析（方案优先，旧 Agent 回落模型槽）
     */
    public RouterJudge(AgentDefRepository agents, ReActTrialExecutor react,
                       RouterJudgePromptFactory judgePrompts, AnswerAggregator answers) {
        this.agents = agents;
        this.react = react;
        this.judgePrompts = judgePrompts;
        this.answers = answers;
    }

    /**
     * 编排根主键：方案试跑没有 Agent 根，返回 null；旧 Agent 试跑返回 Agent 主键。
     *
     * <p>与执行器同名工具同义：判路由的计量归属与日志都按「本轮是谁发起的」记；
     * 方案链路不依赖它（候选与模型都来自 spec）。留在本类是为了不再跨包回调执行器。
     *
     * @param parent 旧 Agent 试跑的 ROUTER；可为 null
     * @return Agent 主键或 null
     */
    private static Long rootId(AgentDef parent) {
        return parent == null ? null : parent.getId();
    }

    /**
     * 调判定模型：有思考出口就走带 {@code progress} 的重载，没有就走不接受推理的同源重载。
     *
     * <p>为什么不一律传 {@code progress}：判定执行器的四参版本是「没人接收推理」的既有契约
     * （调试、单测都按它桩），入口 / 方案分流必须复用同一份判定实现，
     * 因此这里按「本轮是否有推理出口」选重载，而不是另写一条判定路径。
     * 两条重载最终都落到同一段流式判定代码，判定结果与提示词完全一致。
     *
     * @param scope    父 Agent / 父模型 / 会话
     * @param prompt   候选与规则提示词
     * @param userText 用户原文
     * @param limits   判定生效限制
     * @param progress 可空思考出口；空则等价于不带推理的四参调用
     * @return 模型原文（机器 JSON，由调用方解析）
     */
    private String judgeReply(MeterScope scope, String prompt, String userText, EffectiveLimits limits,
                              TrialProgress progress) {
        if (progress == null) {
            return react.judge(scope, prompt, userText, limits);
        }
        // 判定模型的思考边下发边抄一份：入口 / 方案分流的思考框刷新后要还在
        TurnNotes notes = TurnNotes.current();
        TrialProgress sink = notes == null ? progress
                : new ReasoningMemo(progress, notes.judgeReasoning);
        return react.judge(scope, prompt, userText, limits, sink);
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
     * <p>单问在关键字未命中之后、判定模型之前做多项交付覆盖。覆盖已经能定线就不再打模型，
     * 避免先打一次再把结果作废。点名、关键字、手动换线仍不打模型。
     *
     * @param hint 线路提示；可空
     * @param progress 可空的思考出口：判定模型的真实推理经它推出，前端归到入口 / 方案块
     * @return 命中意图 + 是否走了手动换线 + 强制目标是否失效
     */
    public RoutedChoice choose(AgentDef parent, MaRunSpec spec, EffectiveLimits parentLimits,
                               AgentRuntimeConfig runtime, String userText, List<Candidate> candidates,
                               long userId, long sessionId, long deadlineNanos, RouteHint hint,
                               TrialProgress progress) {
        int maxN = runtime.maxRouteChildrenOrDefault();
        boolean forceMissed = false;
        // 1) 手动换线优先于一切：用户已经指定线路，不再花一次判路由，也不再被关键字抢先
        if (hint != null && hint.forced()) {
            Candidate forced = RouterCandidateLoader.byRouteKey(candidates, hint.forceRouteKey());
            if (forced != null) {
                // 强制轮恒为单意图：用户选的是一个候选，不是协作。
                // 理由只作为「来源标记」传给前端识别手动换线，前端不会把它当判定依据展示
                return new RoutedChoice(List.of(new RoutedIntent(forced, userText, "手动换线")), true, false, false);
            }
            forceMissed = true;
        }
        /*
         * 2) 一条消息里的多个问题：先切问，再逐问选目标。
         *
         * <p>为什么必须排在建点 / 关键字 / 判路由之前：那三处都是「整段只留一个目标」的口径
         * ——点名方案会把后一问一起交给该方案，多项交付覆盖也会把整段收给同一个方案，
         * 一旦先跑，第二问就再也出不来（本轮要修的就是这件事）。
         *
         * <p>切问只看「是否另有第二件事」，不看入口的「单问多意图协作」开关：
         * 这里是两个问题各回各家，不是一个问题拆给多人；后者由开关控制。
         */
        List<EntryQuestionSplitter.Question> questions = questionsOf(spec, userText, candidates);
        if (questions.size() >= 2) {
            return chooseForQuestions(parent, spec, parentLimits, runtime, questions, candidates,
                    userId, sessionId, deadlineNanos, forceMissed, progress);
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
            return new RoutedChoice(intents, false, forceMissed, false);
        }
        // 关键字未命中时，多项交付覆盖先于判定模型：已经能定线就不再打一次再作废。
        // 顺序与逐问路径一致，不改提示词，也不按某一类模板另写特例。
        RoutedChoice covered = entryCoveringChoice(spec, userText, candidates, forceMissed);
        if (covered != null) {
            return covered;
        }
        Optional<Long> parentModel = resolveRouterModel(parent, spec);
        if (parentModel.isEmpty()) {
            Candidate first = candidates.get(0);
            // 方案以自己为根：只能靠方案配判路由模型；没配就退化为第一条，文案必须点名是方案缺配置
            String reason = spec != null
                    ? "方案未配置判路由模型（maJudgeModelInstanceId / maJudgeModel），未命中关键词，使用第一条候选"
                    : "未命中关键词且没有可用的判路由模型，使用第一条候选";
            return new RoutedChoice(List.of(new RoutedIntent(first, userText, reason)), false, forceMissed, false);
        }
        try {
            assertParentDeadline(deadlineNanos);
            long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            EffectiveLimits judgeLimits = parentLimits.withTimeoutMs(Math.min(parentLimits.timeoutMs(), remainingMs));
            // 判定模型读「带会话前文的用户侧文本」，模板里的 {{user_query}} 仍走 userText：
            // 前者让「1 / 第二个」这类回答能接上上一轮，后者保持用户原文口径不被历史污染
            String judgeUser = hint != null && hint.judgeInput() != null ? hint.judgeInput() : userText;
            String reply = judgeReply(
                    MeterScope.console(rootId(parent), parentModel.get(), sessionId, userId),
                    judgePrompts.judgePrompt(parent, candidates, runtime, userText, hint, spec), judgeUser, judgeLimits,
                    progress);
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
                    return new RoutedChoice(intents, false, forceMissed, false);
                }
            }
        } catch (TrialFailure ex) {
            if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                throw ex;
            }
            Candidate first = candidates.get(0);
            return new RoutedChoice(List.of(new RoutedIntent(first, userText, "路由模型判定失败，使用第一条")),
                    false, forceMissed, false);
        }
        Candidate first = candidates.get(0);
        return new RoutedChoice(List.of(new RoutedIntent(first, userText, "路由模型未给出有效 route_key，使用第一条")),
                false, forceMissed, false);
    }

    /**
     * 一条消息切成多问时，逐问选目标。所属域：runtime。
     *
     * <p>顺序与单问一致（点名方案 → 关键字 → 多项交付覆盖 → 判路由模型 → 第一条兜底），
     * 但每一处只看**这一问**的原文，不再拿整条消息去截一个目标。
     * 两问打到同一条线路时并成一次执行、子问题接在后面，绝不丢问题。
     *
     * @param parent        编排根；可空
     * @param spec          当前方案（入口）
     * @param parentLimits  父墙钟与轮次上限
     * @param runtime       运行配置
     * @param questions     已切开的问题（≥2）
     * @param candidates    本轮可用候选
     * @param userId        平台用户
     * @param sessionId     当前会话
     * @param deadlineNanos 父墙钟截止
     * @param forceMissed   强制线路已失效，需要带给上层写说明
     * @param progress      可空思考出口
     * @return 按问题顺序排列的意图；{@code multiQuestion=true}
     */
    private RoutedChoice chooseForQuestions(AgentDef parent, MaRunSpec spec, EffectiveLimits parentLimits,
                                            AgentRuntimeConfig runtime,
                                            List<EntryQuestionSplitter.Question> questions,
                                            List<Candidate> candidates, long userId, long sessionId,
                                            long deadlineNanos, boolean forceMissed, TrialProgress progress) {
        /*
         * 目标数上限用平台硬顶（最多 5 路），不读入口配置里的 maxRouteChildren：
         * 那个值是「一个问题最多分给几个目标」的协作上限，拿来截断问题条数会把后一问直接丢掉。
         */
        int cap = Math.max(2, AgentRuntimeConfig.MAX_ROUTE_CHILDREN_CAP);
        List<EntryQuestionSplitter.Question> parts = questions.size() > cap
                ? new ArrayList<>(questions.subList(0, cap)) : new ArrayList<>(questions);
        // 超过硬顶的问题不丢：并进最后一路的子问题，仍由那一路处理
        StringBuilder overflow = new StringBuilder();
        if (questions.size() > cap) {
            for (int i = cap; i < questions.size(); i++) {
                overflow.append('\n').append(questions.get(i).subQuery());
            }
        }
        // 多项交付覆盖要读每个方案的启用成员，逐问重复查库没有意义：本轮取一次
        Map<Long, List<EntrySchemePreference.MemberDuty>> duties = isEntryRouter(spec)
                ? entryMemberDuties(candidates) : Map.of();
        // 同一轮各问共用一份已装配的 ROUTER 前缀；第一次真正打模型时才装配，点名/关键字/覆盖不触发
        RouterJudgePromptFactory.TurnJudgePrompt sharedPrompt = judgePrompts.newTurnPrompt();
        Map<String, RoutedIntent> byKey = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            EntryQuestionSplitter.Question question = parts.get(i);
            RoutedIntent one = routeOneQuestion(parent, spec, parentLimits, runtime, question, candidates,
                    duties, userId, sessionId, deadlineNanos, progress, sharedPrompt);
            String key = one.candidate().route().getRouteKey();
            String normalized = key == null || key.isBlank() ? "q-" + i : key.trim().toLowerCase();
            RoutedIntent existing = byKey.get(normalized);
            if (existing == null) {
                byKey.put(normalized, one);
            } else {
                // 两问命中同一条线路：一次执行带两个子问题，后一问不能被同名去重吃掉
                byKey.put(normalized, new RoutedIntent(existing.candidate(),
                        existing.subQuery() + "\n" + one.subQuery(), existing.reason()));
            }
        }
        List<RoutedIntent> intents = new ArrayList<>(byKey.values());
        if (overflow.length() > 0 && !intents.isEmpty()) {
            int last = intents.size() - 1;
            RoutedIntent tail = intents.get(last);
            intents.set(last, new RoutedIntent(tail.candidate(),
                    tail.subQuery() + overflow, tail.reason()));
        }
        return new RoutedChoice(intents, false, forceMissed, true);
    }

    /**
     * 只针对一个问题的目标选择（多问拆分的每一步）。所属域：runtime。
     *
     * <p>禁止在这里再看整条用户消息：点名方案 / 关键字 / 覆盖判定都只作用于这一问，
     * 否则又会回到「一段话交给一个目标」的老问题。
     *
     * @param question     切出来的一问（text 用于判定，subQuery 用于执行）
     * @param duties       方案 → 启用成员职责；可空
     * @param sharedPrompt 本轮多次判定共用的系统提示；只在真正打模型时装配一次
     * @return 该问命中的目标；永不为空（最后兜底第一条候选）
     */
    private RoutedIntent routeOneQuestion(AgentDef parent, MaRunSpec spec, EffectiveLimits parentLimits,
                                          AgentRuntimeConfig runtime, EntryQuestionSplitter.Question question,
                                          List<Candidate> candidates,
                                          Map<Long, List<EntrySchemePreference.MemberDuty>> duties,
                                          long userId, long sessionId, long deadlineNanos,
                                          TrialProgress progress, RouterJudgePromptFactory.TurnJudgePrompt sharedPrompt) {
        String text = question.text() == null ? "" : question.text().trim();
        String query = question.subQuery() == null || question.subQuery().isBlank() ? text : question.subQuery();
        // 1) 这一问点名了某个方案：只把这一问交给它，不再牵连其它问题
        if (isEntryRouter(spec)) {
            Candidate named = EntrySchemePreference.namedScheme(text, candidates);
            if (named != null) {
                return new RoutedIntent(named, query, EntrySchemePreference.NAMED_REASON);
            }
        }
        // 2) 这一问的多项交付能被某个方案覆盖：交给方案再分发（同一问，不拆）
        Candidate covered = isEntryRouter(spec)
                ? EntrySchemePreference.coveringScheme(text, candidates, duties) : null;
        // 3) 关键字：只看这一问自己的字面命中
        List<AgentRoute> keywordHits = RouteKeywordPicker.matchAll(text,
                candidates.stream().map(Candidate::route).toList(), 1);
        if (!keywordHits.isEmpty()) {
            if (covered != null) {
                return new RoutedIntent(covered, query, EntrySchemePreference.MULTI_REASON);
            }
            return new RoutedIntent(candidateOf(candidates, keywordHits.get(0)), query, "关键字");
        }
        if (covered != null) {
            return new RoutedIntent(covered, query, EntrySchemePreference.MULTI_REASON);
        }
        // 3b) 明确写了「按工作流跑 / 走方案执行」：该类型只有一个候选时直接命中。
        //     只认「运行类动词 + 目标类型」，避免把「工作流的节点怎么配置」这种咨询也当运行请求。
        Candidate byKind = explicitTargetKind(text, candidates);
        if (byKind != null) {
            return new RoutedIntent(byKind, query, EXPLICIT_KIND_REASON);
        }
        // 4) 判路由模型：每问一次，输入就是这一问，避免模型把两问揉成一条
        Optional<Long> parentModel = resolveRouterModel(parent, spec);
        if (parentModel.isPresent()) {
            try {
                assertParentDeadline(deadlineNanos);
                long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                EffectiveLimits judgeLimits = parentLimits.withTimeoutMs(
                        Math.min(parentLimits.timeoutMs(), remainingMs));
                // 系统提示复用本轮已装配的前缀，用户侧只送这一问
                String reply = judgeReply(
                        MeterScope.console(rootId(parent), parentModel.get(), sessionId, userId),
                        sharedPrompt.forQuestion(parent, candidates, runtime, null, spec, text),
                        text, judgeLimits, progress);
                List<String> keys = candidates.stream()
                        .map(item -> item.route().getRouteKey())
                        .filter(key -> key != null && !key.isBlank())
                        .toList();
                List<RouteDecisionParser.Item> parsed = RouteDecisionParser.parse(reply, keys, 1, text);
                for (RouteDecisionParser.Item item : parsed) {
                    Candidate hit = RouterCandidateLoader.byRouteKey(candidates, item.routeKey());
                    if (hit != null) {
                        // 理由随判定一次产出；为空就留空，由前端降级成中性句，不在这里补系统标签
                        return new RoutedIntent(hit, query, item.reason());
                    }
                }
            } catch (TrialFailure ex) {
                if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                    throw ex;
                }
                log.info("工作台入口单问判定失败，使用第一条候选 text={} reason={}", text, ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn("工作台入口单问判定异常 text={} type={} cause={}", text,
                        ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            }
        }
        return new RoutedIntent(candidates.get(0), query, "该问题未命中专用目标，使用第一条候选");
    }

    /**
     * 这一问是否明确要求「用工作流 / 走方案」执行；该类型只有一个候选时返回它。所属域：runtime。
     *
     * <p>只认「目标类型 + 运行类动词」的组合：用户说「按工作流跑一次」意图很明确，
     * 却可能因为工作流候选的 route_key / 意图描述里没有「工作流」二字而落空；
     * 反过来，「工作流的节点怎么配置」这种咨询带不上运行动词，不会被误判成运行请求。
     *
     * @param text       这一问的原文
     * @param candidates 本轮可用候选
     * @return 唯一同类型候选；没有或同类有多个时为空
     */
    private static Candidate explicitTargetKind(String text, List<Candidate> candidates) {
        if (text == null || text.isBlank() || candidates == null) {
            return null;
        }
        boolean runVerb = containsAny(text, List.of("跑", "执行", "运行", "走一遍", "试一下", "试试"));
        if (!runVerb) {
            return null;
        }
        if (text.contains("工作流")) {
            Candidate only = null;
            for (Candidate candidate : candidates) {
                if (!candidate.isWorkflowTarget()) {
                    continue;
                }
                if (only != null) {
                    return null;
                }
                only = candidate;
            }
            return only;
        }
        if (text.contains("方案")) {
            Candidate only = null;
            for (Candidate candidate : candidates) {
                if (!candidate.isMaTarget()) {
                    continue;
                }
                if (only != null) {
                    return null;
                }
                only = candidate;
            }
            return only;
        }
        return null;
    }

    /** 用户明确点名「按工作流 / 走方案」时写进依据的一句人话。 */
    private static final String EXPLICIT_KIND_REASON = "用户明确要求按该运行方式执行";

    /** 方案内层按成员职责覆盖命中时写进依据的一句人话。 */
    private static final String COVERED_REASON = "问句点名了该成员的职责范围，按方案成员分工直接派出";

    /**
     * 方案链路：这一问按成员名称 / 简介打中了哪几名候选。所属域：runtime。
     *
     * <p>口径复用 {@link EntrySchemePreference#coveredBy}（与入口 coveringScheme 同源），
     * 这里只负责三件衔接工作：
     * <ol>
     *   <li>把当前候选拍成「成员名称 + 简介」并保持候选顺序；</li>
     *   <li>只取子 Agent 候选参与判定——方案 / 工作流目标没有「成员职责」语义，
     *       它们的说明是线路说明，按名称匹配容易误伤，留给判路由模型判断；</li>
     *   <li>命中顺序就是派发顺序（上层再按 sortOrder 排一次，两者一致）。</li>
     * </ol>
     *
     * <p>旧 {@code agent_route} 口径（{@code spec == null}）恒返回空表：那条链的候选本来就是
     * 单兵池，没有「方案成员分工」这层语义，保持现网行为不变。
     *
     * @param spec       当前方案执行来源；可空（旧口径）
     * @param userText   用户原问
     * @param candidates 本轮可用候选
     * @return 按候选顺序命中的子 Agent 候选；没有命中或非方案链路时为空表
     */
    private List<Candidate> coveredMembers(MaRunSpec spec, String userText, List<Candidate> candidates) {
        if (spec == null || candidates == null || candidates.isEmpty() || userText == null || userText.isBlank()) {
            return List.of();
        }
        // 只有子 Agent 候选参与：方案 / 工作流目标的说明是线路说明，不按成员名称匹配
        List<Candidate> plain = new ArrayList<>();
        List<EntrySchemePreference.MemberDuty> duties = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.agent() == null || candidate.delegatedTarget()) {
                continue;
            }
            plain.add(candidate);
            duties.add(new EntrySchemePreference.MemberDuty(
                    candidate.agent().getName(), candidate.agent().getDescription()));
        }
        boolean[] hit = EntrySchemePreference.coveredBy(userText, duties);
        List<Candidate> covered = new ArrayList<>();
        for (int i = 0; i < hit.length; i++) {
            if (hit[i]) {
                covered.add(plain.get(i));
            }
        }
        return covered;
    }

    /**
     * 判断文本是否包含任一标记。
     *
     * @param text    原文；可空
     * @param markers 标记词
     * @return true 表示命中
     */
    private static boolean containsAny(String text, List<String> markers) {
        if (text == null) {
            return false;
        }
        for (String marker : markers) {
            if (marker != null && !marker.isEmpty() && text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 入口候选的展示名，供切问判断「这一段是否在指向另一条线路」。所属域：runtime。
     *
     * @param candidates 本轮可用候选
     * @return 展示名（去空、去重）；无名称时候选为空表
     */
    private List<String> candidateNames(List<Candidate> candidates) {        List<String> names = new ArrayList<>();
        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            String name = candidate.agent() == null ? "" : candidate.agent().getName();
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.length() >= 2 && !names.contains(trimmed)) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /**
     * 切问入口：只有工作台入口才切，其它方案仍按「一段话一个诉求」执行。所属域：runtime。
     *
     * @param spec       当前方案；可空
     * @param userText   用户原话
     * @param candidates 本轮可用候选
     * @return 至少一条问题；非入口恒为一条
     */
    private List<EntryQuestionSplitter.Question> questionsOf(MaRunSpec spec, String userText,
                                                            List<Candidate> candidates) {
        if (!isEntryRouter(spec)) {
            return List.of(new EntryQuestionSplitter.Question(userText, userText));
        }
        return EntryQuestionSplitter.split(userText, candidateNames(candidates));
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
                false, forceMissed, false);
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
                false, forceMissed, false);
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

    /**
     * 当前执行来源是不是工作台入口。列上的模板码为准，不看配置 JSON 里残留的串行码。
     *
     * <p>public 是为了让执行器在开观测根时复判一次（判路由是否挂网关根），
     * 口径仍只有这一处；提示词工厂里另有一份同义私有判定，本刀不合并提示词代码。
     *
     * @param spec 方案执行来源；可空
     * @return true 表示入口方案
     */
    public static boolean isEntryRouter(MaRunSpec spec) {
        return spec != null && AgentRuntimeConfig.isEntryRouterTemplate(spec.template());
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
     * @param progress     可空的思考出口：判定模型的真实推理经它推出
     * @return 按判定数组顺序排列的意图；非空
     * @throws TrialFailure 判路由不可用或结果不可用；墙钟超时原样抛出
     */
    public List<RoutedIntent> chooseOrdered(AgentDef parent, MaRunSpec spec, EffectiveLimits parentLimits,
                                            AgentRuntimeConfig runtime, String userText,
                                            List<Candidate> candidates, long userId, long sessionId,
                                            long deadlineNanos, TrialProgress progress) {
        int maxN = runtime.maxRouteChildrenOrDefault();
        /*
         * 0) 方案链路先做「成员职责覆盖」：与入口 coveringScheme 同一套名称 / 简介口径，
         *    只是这里数的是「当前方案有几名候选被打中」，不是「有几个方案能覆盖」。
         *
         * <p>为什么排在关键字与判定模型之前：本模板的候选是方案成员，route_key 多为
         * {@code MA_KB_Policy} 这类英文键，对中文问句永远不命中；而判定模型会把握手式的
         * 「整理要点 + 起草报告」收成「一件事的先后步骤」只派一位，把制度检索那一路丢掉。
         * 命中几位就派几位，不再让模型决定条数。
         *
         * <p>命中 1 位也直接派：这一位已经是被问句明确对应的成员，再问一次模型只是多一次
         * 出站调用与一次漂移机会。只有一位都没打中，才回到现网的关键字 → 判定模型。
         *
         * <p>只对方案链路生效（{@code spec != null}）；旧 {@code agent_route} 口径一行不变。
         */
        List<Candidate> covered = coveredMembers(spec, userText, candidates);
        if (!covered.isEmpty()) {
            List<RoutedIntent> coveredIntents = new ArrayList<>(covered.size());
            for (Candidate hit : covered) {
                // 每一块交付都按整句派：被覆盖的成员各自去处理自己那一块，子问题不再由模型切
                coveredIntents.add(new RoutedIntent(hit, userText, COVERED_REASON));
            }
            return coveredIntents;
        }
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
            reply = judgeReply(
                    MeterScope.console(rootId(parent), parentModel.get(), sessionId, userId),
                    judgePrompts.judgePrompt(parent, candidates, runtime, userText, null, spec), userText, judgeLimits,
                    progress);
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
        return keepRuleCovered(intents, coveredMembers(spec, userText, candidates));
    }

    /**
     * 判定模型的结果过一遍规则校验：规则已命中的成员不能被模型丢掉。所属域：runtime。
     *
     * <p>覆盖判定已经在模型之前返回，走到说明<b>一位都没被规则打中</b>，正常不会有补齐。
     * 这里仍做一次并集：同一套匹配在本轮内可能因为调用顺序变化（例如先跑了关键字）
     * 而让规则命中与模型结果并存，此时以「规则命中的都要留下」为准，
     * 模型额外给的目标也保留——两边都不丢，顺序仍由上层按 sortOrder 定。
     *
     * <p>不在候选里的 route_key 在上一步已按现网丢弃，这里不碰。
     *
     * @param intents 判定模型给出的意图（已映射回候选）
     * @param covered 规则命中的候选
     * @return 并集；规则集为空时原样返回模型结果
     */
    private static List<RoutedIntent> keepRuleCovered(List<RoutedIntent> intents, List<Candidate> covered) {
        if (covered == null || covered.isEmpty()) {
            return intents;
        }
        Set<Long> present = new LinkedHashSet<>();
        for (RoutedIntent intent : intents) {
            Long key = routeIdentity(intent.candidate());
            if (key != null) {
                present.add(key);
            }
        }
        List<RoutedIntent> merged = new ArrayList<>(intents);
        for (Candidate hit : covered) {
            Long key = routeIdentity(hit);
            if (key != null && present.add(key)) {
                merged.add(new RoutedIntent(hit, "", COVERED_REASON));
            }
        }
        return merged;
    }

    /**
     * 候选的稳定身份：方案链路按 ma_candidate 主键，旧口径按子 Agent 主键。所属域：runtime。
     *
     * @param candidate 候选；可空
     * @return 身份键；都取不到时为 null
     */
    private static Long routeIdentity(Candidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (candidate.route() != null && candidate.route().getId() != null) {
            return candidate.route().getId();
        }
        return candidate.agent() == null ? null : candidate.agent().getId();
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
     * 按 route_key 定位候选（关键字命中用）。关键字选择器返回的是路由行本身，
     * 因此优先按 route_key 取；MA 候选没有 child_agent_id，只有这条路可靠。
     *
     * @param candidates 可用候选
     * @param route      关键字命中的路由行
     * @return 对应候选；键对不上时退回首位，保证不因脏数据中断一轮
     */
    private Candidate candidateOf(List<Candidate> candidates, AgentRoute route) {
        Candidate byKey = RouterCandidateLoader.byRouteKey(candidates, route.getRouteKey());
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

}

package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.RouterAnswerMode;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.runtime.application.SystemPromptAssembler;
import com.agenthub.runtime.application.TrialBindingLoader;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.CompeteVerdictParser;
import com.agenthub.runtime.domain.service.LimitHitReasons;
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

import static com.agenthub.runtime.application.router.ChildOutcome.hasAnswer;
import static com.agenthub.runtime.application.router.ChildOutcome.keyOf;

/**
 * 终答汇总：CONCAT 拼接 / SUMMARIZE 父模型整合 / VOTE 投票选一路原文。所属域：runtime。
 *
 * <p>为什么独立成类：这四种策略（含各自降级）原本散在 3000 行的模板执行器里，
 * 与模板分支、子执行、观测缠绕在一起。抽出来后模板执行器只负责「跑完子 Agent 拿到 outcomes」，
 * 汇总语义集中一处，便于单独审阅。
 *
 * <p><b>不可动的契约</b>：
 * <ul>
 *   <li>SUMMARIZE / VOTE 失败一律降级 CONCAT，但墙钟超时（finishReason=TIMEOUT）必须原样抛出，不降级；</li>
 *   <li>VOTE 票池 0 路 / 1 路不调模型，直接走既有口径；</li>
 *   <li>降级中文说明的文案不变；SUMMARIZE 的正文改为流式投递（边收边推），
 *       只有未投递时才在末尾补推整段，避免同一段正文被推两次。</li>
 * </ul>
 *
 * <p>构造方式：由 {@code RouterTrialExecutor} 在自身构造器内 new（非 Spring Bean），
 * 这样模板执行器的构造器签名不变，既有单测无需改动。
 */
public class AnswerAggregator {

    private static final Logger log = LoggerFactory.getLogger(AnswerAggregator.class);

    /** 投票时给评委看的单个子终答长度硬顶：超过就截断，避免一次评选吃掉父墙钟 */
    private static final int VOTE_ANSWER_LIMIT = 3000;

    /** 判路由 / 汇总 / 投票的模型来源解析 */
    private final TrialBindingLoader loader;

    /** 父 Agent 的平台前缀（判路由 / 汇总 / 投票共用同一份用途模板） */
    private final SystemPromptAssembler prompts;

    /** 汇总与投票的模型调用 */
    private final ReActTrialExecutor react;

    /**
     * @param loader  解析判路由 / 汇总 / 投票用途的模型实例
     * @param prompts 拼平台前缀，保证三段提示词口径一致
     * @param react   调模型的执行器（summarize / judge）
     */
    public AnswerAggregator(TrialBindingLoader loader, SystemPromptAssembler prompts, ReActTrialExecutor react) {
        this.loader = loader;
        this.prompts = prompts;
        this.react = react;
    }

    /**
     * 按汇总策略产出终答。CONCAT 按序拼接；SUMMARIZE 用父模型一轮汇总；
     * VOTE 把成功子终答当票池选 1 路原文。等齐后再出终答（不做「先完成先推段」）。
     *
     * @param parent        编排根
     * @param spec          方案来源；可空
     * @param parentLimits  父墙钟与轮次上限
     * @param runtime       运行配置（汇总策略从这里读）
     * @param userText      用户原问
     * @param outcomes      全部子结果（含失败 / 跳过占位）
     * @param intents       派发时的意图列表
     * @param deadlineNanos 父墙钟截止
     * @param progress      可空
     * @param trace         可空
     * @param routeSpan     ROUTE 步骤 id；可空（汇总/投票 LLM 挂它下面）
     * @return 终答 + 需要进 SYSTEM 事件 / 时间线的说明
     * @throws TrialFailure 墙钟超时（不降级）
     */
    public AggregateOutcome finishAnswer(AgentDef parent, MaRunSpec spec, EffectiveLimits parentLimits,
                                         AgentRuntimeConfig runtime, String userText, List<ChildOutcome> outcomes,
                                         List<RoutedIntent> intents, long userId, long sessionId, long deadlineNanos,
                                         TrialProgress progress, ObsTraceScope trace, Long routeSpan) {
        String concat = concatAnswers(outcomes);
        RouterAnswerMode mode = runtime.answerModeEnum();
        List<String> notices = new ArrayList<>();
        String aggregateLine = null;
        /**
         * 汇总正文是否已经在汇总那一步边收边推给页面。
         * <p>true 时本方法末尾不得再整段推一次，否则页面会出现「流式正文 + 整段重影」；
         * 只有汇总没投递（无父模型 / 推正文前就失败降级）时才补推拼接正文。
         */
        boolean answerDelivered = false;
        if (mode == RouterAnswerMode.VOTE) {
            // 票池只收「有正文的成功子」；失败 / 跳过子仍占位但不参与投票
            List<ChildOutcome> pool = outcomes.stream().filter(item -> item.hasAnswer()).toList();
            VotePick pick = voteWinner(parent, spec, userText, pool, userId, sessionId, deadlineNanos,
                    parentLimits, progress, trace, routeSpan);
            int failed = 0;
            for (ChildOutcome outcome : outcomes) {
                if (outcome.error() != null && !outcome.skipped()) {
                    failed++;
                }
            }
            amendVoteAttrs(trace, routeSpan, pick, pool.size(), failed);
            notices.addAll(pick.notices());
            aggregateLine = pick.segmentLine();
            if (pick.winner() != null) {
                // 终答＝赢家原文，不做二次改写 / 拼接；token 与产物仍按全部子汇总。
                // 各位的结论已经按参与者推过，这里再推赢家会把同一段正文再贴一次。
                TrialTurn turn = mergeTurns(outcomes, pick.winner().turn().getAssistantText());
                return new AggregateOutcome(turn, notices, aggregateLine,
                        indexOfOutcome(outcomes, pick.winner()));
            }
        }
        if (mode == RouterAnswerMode.SUMMARIZE && outcomes.size() >= 1) {
            // 汇总模型与判路由同源：方案里配了就用方案的，否则回落 Agent 的 ROUTER/CHAT 槽
            Optional<Long> parentModel = resolveRouterModel(parent, spec);
            if (parentModel.isPresent()) {
                // 有出口就走流式投递：汇总常是整轮最后一步，一次性出全文会让人以为卡住
                AssistantRelay relay = progress == null ? null : new AssistantRelay(progress);
                try {
                    RouterClock.assertBefore(deadlineNanos);
                    if (progress != null) {
                        // 方案有自己的汇总结论时用「方案结论」标记，消息流据此在子专家之后再出一块，
                        // 而不是把汇总正文塞进前面的分流块。旧 Agent 路径仍用原来的汇总提示。
                        progress.route(spec != null
                                ? "方案结论：" + rootName(parent, spec)
                                : "终答汇总中…");
                    }
                    if (trace != null) {
                        // 汇总 LLM 挂在 ROUTE 下，与判路由 LLM 同级兄弟
                        trace.bindParent(routeSpan);
                    }
                    long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                    EffectiveLimits summarizeLimits = parentLimits.withTimeoutMs(
                            Math.min(parentLimits.timeoutMs(), remainingMs));
                    MeterScope scope = MeterScope.console(rootId(parent), parentModel.get(), sessionId, userId);
                    String prompt = summarizePrompt(rootName(parent, spec), userText);
                    String input = summarizeUser(userText, outcomes);
                    String summary;
                    if (relay == null) {
                        // 没有增量出口（如计划执行模式的收尾汇总）：保持整段返回
                        summary = react.summarize(scope, prompt, input, summarizeLimits);
                    } else {
                        ReActTrialExecutor.StreamedAnswer streamed =
                                react.summarizeStreamed(scope, prompt, input, summarizeLimits, relay);
                        summary = streamed.text();
                        // 已被 relay 推给页面：标记住，避免下面再整段补推
                        answerDelivered = streamed.delivered();
                    }
                    if (summary != null && !summary.isBlank()) {
                        TrialTurn turn = mergeTurns(outcomes, summary.trim());
                        // 正文已经边收边推过，这里再推就是重影；没推过（relay 为空 / 流里没正文）才补
                        if (progress != null && !answerDelivered) {
                            progress.assistant(turn.getAssistantText());
                            answerDelivered = true;
                        }
                        return new AggregateOutcome(turn, notices, aggregateLine, -1);
                    }
                    if (progress != null) {
                        progress.route("汇总无正文，已降级为拼接");
                    }
                } catch (TrialFailure ex) {
                    if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                        throw ex;
                    }
                    if (progress != null) {
                        // 降级说明会进时间线：只给可读短句，异常原文进日志（带栈）
                        log.warn("Router 汇总失败 sessionId={} err={}", sessionId, ex.getMessage(), ex);
                        progress.route("汇总暂时不可用，已改用分段拼接。");
                    }
                } catch (RuntimeException ex) {
                    // 汇总失败降级为拼接：用户面只要一句短话，stderr 里要有完整栈
                    log.warn("Router 汇总异常降级 sessionId={} type={} cause={}",
                            sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                    if (progress != null) {
                        progress.route("汇总失败，已降级为拼接");
                    }
                } finally {
                    // 中途失败也可能已经推过一部分正文：失败路径同样记下，避免末尾再叠一整段
                    if (relay != null && relay.delivered()) {
                        answerDelivered = true;
                    }
                    if (trace != null) {
                        trace.bindParent(null);
                    }
                }
            } else if (progress != null) {
                progress.route("无父模型可汇总，已降级为拼接");
            }
        }
        TrialTurn turn = mergeTurns(outcomes, concat);
        // 多子 / 投票 / 汇总已经按参与者交出过结论：再推拼接稿会把子正文复制一遍。
        // 只剩「单子失败、当时没有流式正文」才补一段失败说明。
        boolean participantsAlreadyShown = outcomes.size() > 1
                || mode == RouterAnswerMode.SUMMARIZE
                || mode == RouterAnswerMode.VOTE;
        boolean needPush = !participantsAlreadyShown
                && outcomes.size() == 1
                && outcomes.get(0).error() != null;
        // 汇总已经推过正文时不再补推：前半段是流式原文、后半段是拼接全文，拼起来会是一段错乱答复。
        if (progress != null && needPush && !answerDelivered) {
            progress.assistant(turn.getAssistantText());
        }
        return new AggregateOutcome(turn, notices, aggregateLine, -1);
    }

    /**
     * 判路由 / 汇总模型解析：方案优先，旧 Agent 试跑才回落 Agent 模型槽。
     *
     * <p>方案链路没有 Agent 根：{@code parent} 为 null，模型只认方案的 judge 槽；
     * 只有旧单兵 ROUTER 试跑才会带 parent 去读 agent_def。
     *
     * @param parent 旧 Agent 试跑的 ROUTER；方案链路为 null
     * @param spec   方案执行来源；可空
     * @return 模型实例 id；都不可用时为空
     */
    public Optional<Long> resolveRouterModel(AgentDef parent, MaRunSpec spec) {
        if (spec != null && spec.judgeModelInstanceId() != null) {
            return Optional.of(spec.judgeModelInstanceId());
        }
        if (parent == null || parent.getId() == null) {
            return Optional.empty();
        }
        return loader.findRouterModel(parent.getId());
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
     * 投票选优：成功子终答进票池，评选模型选 1 路原文。
     *
     * <p>与 COMPETE 的边界：COMPETE 是模板层的「同题竞争」，终态恒只出 winner；
     * 这里是<b>汇总策略</b>，票池可来自判路由的不同 sub_query，选不出就降级 CONCAT。
     *
     * @param parent        编排根
     * @param spec          方案来源；可空
     * @param userText      用户原问
     * @param pool          成功子结果，顺序即候选顺序
     * @param deadlineNanos 父墙钟截止
     * @param progress      可空
     * @param trace         可空
     * @param routeSpan     ROUTE 步骤 id；可空
     * @return 赢家（null 表示降级）+ 说明
     * @throws TrialFailure 墙钟超时（不降级）
     */
    private VotePick voteWinner(AgentDef parent, MaRunSpec spec, String userText, List<ChildOutcome> pool,
                                long userId, long sessionId, long deadlineNanos, EffectiveLimits parentLimits,
                                TrialProgress progress, ObsTraceScope trace, Long routeSpan) {
        if (pool.isEmpty()) {
            return VotePick.failed("投票：没有成功子终答，已按分段拼接");
        }
        // 只有一路成功：投票没有意义，直接出该子终答，省一次模型出站
        if (pool.size() == 1) {
            ChildOutcome only = pool.get(0);
            return new VotePick(only, false,
                    List.of("投票：仅一路成功（" + keyOf(only) + "），已跳过投票直接出终答"),
                    "投票：仅 1 个成功子，跳过投票（winner=" + keyOf(only) + "）");
        }
        Optional<Long> judgeModel = resolveRouterModel(parent, spec);
        if (judgeModel.isEmpty()) {
            return VotePick.failed("投票：未配置评选模型（maJudgeModelInstanceId / maJudgeModel），已降级为分段拼接");
        }
        try {
            RouterClock.assertBefore(deadlineNanos);
            long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            EffectiveLimits voteLimits = parentLimits.withTimeoutMs(Math.min(parentLimits.timeoutMs(), remainingMs));
            if (progress != null) {
                progress.route("终答投票中：比较 " + pool.size() + " 份子终答…");
            }
            if (trace != null) {
                // 投票 LLM 挂在 ROUTE 下，与判路由 / 汇总 LLM 同级兄弟
                trace.bindParent(routeSpan);
            }
            String reply = react.judge(
                    MeterScope.console(rootId(parent), judgeModel.get(), sessionId, userId),
                    votePrompt(rootName(parent, spec), userText, pool), voteUserText(userText, pool), voteLimits);
            // 解析契约与 COMPETE 共用同一份 Parser：两处都是 {"winner","reason"}，禁止各写一套格式
            Optional<CompeteVerdictParser.Verdict> verdict = CompeteVerdictParser.parse(reply,
                    pool.stream().map(ChildOutcome::keyOf).toList());
            if (verdict.isEmpty()) {
                return VotePick.failed("投票：评选模型未给出合法赢家，已降级为分段拼接");
            }
            String winnerKey = verdict.get().winnerKey();
            ChildOutcome winner = pool.stream()
                    .filter(item -> winnerKey.equalsIgnoreCase(keyOf(item)))
                    .findFirst()
                    .orElse(null);
            if (winner == null) {
                return VotePick.failed("投票：评选模型未给出合法赢家，已降级为分段拼接");
            }
            String reason = verdict.get().reason().isBlank() ? "评选模型未给出理由" : verdict.get().reason();
            return new VotePick(winner, false,
                    List.of("投票：winner=" + keyOf(winner) + "（" + reason + "）"),
                    "投票：winner=" + keyOf(winner) + "，理由：" + reason);
        } catch (TrialFailure ex) {
            // 墙钟超时是统一硬拦，不降级：父预算已被吃掉，后续推送不可信
            if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                throw ex;
            }
            log.warn("投票评选失败 sessionId={} err={}", sessionId, ex.getMessage(), ex);
            return VotePick.failed("投票：评选模型暂时不可用，已降级为分段拼接");
        } catch (RuntimeException ex) {
            log.warn("投票评选异常 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            return VotePick.failed("投票：评选模型调用异常，已降级为分段拼接");
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
    }

    /**
     * 把投票结论补写到 ROUTE 的 attrs：泳道据此显示「投票（VOTE · winner）」。
     *
     * <p>ROUTE 在扇出阶段就已收尾，这里只追加字段，不改任何终态 / 正文列。
     *
     * @param trace     可空
     * @param routeSpan ROUTE 步骤 id；可空
     * @param pick      投票结论
     * @param ok        成功子数（票池大小）
     * @param failed    失败子数
     */
    private void amendVoteAttrs(ObsTraceScope trace, Long routeSpan, VotePick pick, int ok, int failed) {
        if (trace == null || routeSpan == null) {
            return;
        }
        String winner = pick.winner() == null ? "" : keyOf(pick.winner());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ma.vote.winner", winner);
        fields.put("ma.vote.degraded", pick.degraded() ? "true" : "false");
        fields.put("ma.vote.ok", String.valueOf(ok));
        fields.put("ma.vote.fail", String.valueOf(failed));
        trace.amendAttrs(routeSpan, fields);
    }

    /**
     * 投票的评委提示词：只要求输出 JSON，不辩论、不改写候选正文。
     *
     * @param rootName 编排根名（方案名 / 旧 Agent 名）；可空
     * @param userText 用户原问
     * @param pool     成功子结果
     * @return 系统提示
     */
    private String votePrompt(String rootName, String userText, List<ChildOutcome> pool) {
        StringBuilder prompt = new StringBuilder();
        // 平台前缀沿用 ROUTER 用途模板，避免与判路由 / 汇总两套前缀并存；
        // 装配器异常 / 未装配时返回 null，不能把投票提示词整段弄崩
        String assembled = prompts.buildRoot(rootName, userText);
        if (assembled != null && !assembled.isBlank()) {
            prompt.append(assembled).append("\n\n");
        }
        prompt.append("你是终答投票的评审。下面有 ").append(pool.size())
                .append(" 位专家针对用户问题给出的最终答复（可能分别回答了不同子问题）。\n")
                .append("请选出最能完整、准确回应用户问题的一份；只评价，不改写。\n")
                .append("只输出 JSON 对象：{\"winner\":\"<route_key>\",\"reason\":\"不超过 30 字的中文理由\"}。\n")
                .append("不要解释、不要 markdown、不要提及专家姓名。\n")
                .append("候选：\n");
        for (ChildOutcome outcome : pool) {
            prompt.append("- ").append(keyOf(outcome));
            // 意图说明单一真源：读 Agent 简介，不再读 ma_candidate.intent_desc
            String brief = RouterTexts.descriptionOf(outcome.intent().candidate().agent());
            if (brief != null) {
                prompt.append('：').append(brief);
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    /**
     * 投票的用户侧正文：用户原问 + 各成功子终答（单条硬顶，防一次投票吃掉全部预算）。
     *
     * @param userText 用户原问
     * @param pool     成功子结果
     * @return 用户消息正文
     */
    private String voteUserText(String userText, List<ChildOutcome> pool) {
        StringBuilder text = new StringBuilder();
        text.append("用户原问：\n").append(userText == null ? "" : userText.trim()).append("\n\n");
        text.append("各子终答：\n");
        for (ChildOutcome outcome : pool) {
            text.append("### [").append(keyOf(outcome)).append("] ").append(RouterTexts.sectionTitle(outcome)).append('\n');
            text.append(TrialTexts.clip(outcome.turn().getAssistantText(), VOTE_ANSWER_LIMIT)).append("\n\n");
        }
        return text.toString();
    }

    /**
     * CONCAT：按 outcomes 顺序拼接；跳过与失败写成明确占位，不伪装成正常答复。
     *
     * @param outcomes 子结果
     * @return 拼接正文
     */
    private String concatAnswers(List<ChildOutcome> outcomes) {
        StringBuilder text = new StringBuilder();
        for (ChildOutcome outcome : outcomes) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append("### ").append(RouterTexts.sectionTitle(outcome)).append('\n');
            if (outcome.skipped()) {
                // 跳过不是失败：明确写「已跳过」，避免用户以为子 Agent 跑挂了
                text.append("（已跳过）").append(outcome.error());
            } else if (outcome.error() != null) {
                text.append("（执行失败）").append(outcome.error());
            } else if (outcome.turn() != null && outcome.turn().getAssistantText() != null
                    && !outcome.turn().getAssistantText().isBlank()) {
                text.append(outcome.turn().getAssistantText().trim());
            } else {
                text.append("（无实质回答）");
            }
        }
        return text.toString();
    }

    /**
     * 合并子结果为一份终答载体：正文用调用方给的文本，token 取和，工具产物取最大，traces 拼接。
     *
     * <p>公开给模板执行器：COMPETE 与评审环的终答也要按同一口径汇总 token / traces，
     * 不能各写一套（否则同一轮的不同模板在计量上会不一致）。
     *
     * @param outcomes      子结果
     * @param assistantText 终答正文
     * @return 合并结果
     */
    public TrialTurn mergeTurns(List<ChildOutcome> outcomes, String assistantText) {
        TrialTurn merged = new TrialTurn();
        merged.setAssistantText(assistantText == null ? "" : assistantText);
        int prompt = 0;
        int completion = 0;
        int toolMax = 0;
        List<TrialTrace> traces = new ArrayList<>();
        for (ChildOutcome outcome : outcomes) {
            if (outcome.turn() == null) {
                continue;
            }
            if (outcome.turn().getPromptTokens() != null) {
                prompt += outcome.turn().getPromptTokens();
            }
            if (outcome.turn().getCompletionTokens() != null) {
                completion += outcome.turn().getCompletionTokens();
            }
            toolMax = Math.max(toolMax, outcome.turn().getToolResultMaxChars());
            merged.mergeFiles(outcome.turn().getFiles());
            if (outcome.turn().getTraces() != null) {
                traces.addAll(outcome.turn().getTraces());
            }
        }
        merged.setPromptTokens(prompt);
        merged.setCompletionTokens(completion);
        merged.setToolResultMaxChars(toolMax);
        merged.setTraces(traces);
        return merged;
    }

    /**
     * 汇总的系统提示：沿用 ROUTER 用途前缀，再追加「综合成一篇中文答复」的硬要求。
     *
     * @param rootName 编排根名（方案名 / 旧 Agent 名）；可空
     * @param userText 用户原问（模板里的 {{user_query}} 需要值）
     * @return 系统提示
     */
    private String summarizePrompt(String rootName, String userText) {
        StringBuilder prompt = new StringBuilder();
        // ROUTER 用途模板里的 {{user_query}} 需要本轮原问；否则父汇总阶段读不到用户问了什么
        // 平台前缀沿用 ROUTER 用途模板，避免与判路由 / 汇总两套前缀并存；
        // 装配器异常 / 未装配时返回 null，不能把汇总提示词整段弄崩
        String assembled = prompts.buildRoot(rootName, userText);
        if (assembled != null && !assembled.isBlank()) {
            prompt.append(assembled).append("\n\n");
        }
        prompt.append("你是路由父助手。下方给出用户原问与各子 Agent 的回答（已打码敏感信息）。\n");
        prompt.append("请综合成一篇连贯、完整的中文答复，不要罗列「子 Agent」字样，不要编造子回答中没有的事实。\n");
        return prompt.toString();
    }

    /**
     * 汇总的用户侧正文：用户原问 + 各子回答（跳过 / 失败写占位）。
     *
     * @param userText 用户原问
     * @param outcomes 子结果
     * @return 用户消息正文
     */
    private String summarizeUser(String userText, List<ChildOutcome> outcomes) {
        StringBuilder text = new StringBuilder();
        text.append("用户原问：\n").append(userText == null ? "" : userText.trim()).append("\n\n");
        text.append("各子回答：\n");
        for (ChildOutcome outcome : outcomes) {
            text.append("- ").append(RouterTexts.sectionTitle(outcome)).append("：");
            if (outcome.skipped()) {
                text.append("（已跳过）").append(outcome.error());
            } else if (outcome.error() != null) {
                text.append("（失败）").append(outcome.error());
            } else if (outcome.turn() != null) {
                text.append(outcome.turn().getAssistantText() == null ? "" : outcome.turn().getAssistantText().trim());
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * 在 outcomes 里按对象身份定位赢家下标。
     *
     * <p>用 {@code ==} 而不是 equals：需要的是「同一份子结果」，不是内容恰好相同的另一份。
     *
     * @param outcomes 子结果列表
     * @param target   赢家
     * @return 下标；找不到为 -1
     */
    private static int indexOfOutcome(List<ChildOutcome> outcomes, ChildOutcome target) {
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 汇总正文中转站。所属域：runtime（应用层）。
     *
     * <p>职责：把汇总模型的增量原样转发给页面，同时记下「正文是否已经推过」。
     * 为什么要记：汇总成功时调用方不能再整段补推（会重影）；
     * 但汇总在推正文之前就失败降级时，末尾仍要补推拼接正文，否则页面空白。
     *
     * <p>只转发判路由 / 汇总用得上的五类回调。汇总调用不挂工具、不产计划 / 产物 / 子计划，
     * 其余默认空实现直接继承 {@link TrialProgress}，不必逐个补齐。
     */
    private static final class AssistantRelay implements TrialProgress {

        /** 真正的页面出口 */
        private final TrialProgress delegate;

        /** 是否已经推过非空正文；用 volatile 是因为增量来自模型响应线程 */
        private volatile boolean delivered;

        /**
         * @param delegate 页面出口；非空
         */
        private AssistantRelay(TrialProgress delegate) {
            this.delegate = delegate;
        }

        /**
         * @return true 表示正文已经推给页面，调用方不得再整段补推
         */
        boolean delivered() {
            return delivered;
        }

        /** {@inheritDoc} */
        @Override
        public void phase(String phase) {
            delegate.phase(phase);
        }

        /** {@inheritDoc} */
        @Override
        public void route(String summary) {
            delegate.route(summary);
        }

        /** {@inheritDoc} */
        @Override
        public void reasoning(String delta) {
            delegate.reasoning(delta);
        }

        /** {@inheritDoc} */
        @Override
        public void tool(String name, String arguments, String result, boolean success) {
            delegate.tool(name, arguments, result, success);
        }

        /** {@inheritDoc} */
        @Override
        public void assistant(String delta) {
            if (delta != null && !delta.isEmpty()) {
                delivered = true;
            }
            delegate.assistant(delta);
        }
    }
}

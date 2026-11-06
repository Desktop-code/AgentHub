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
 *   <li>降级中文说明的文案与「是否推 assistant」的时机保持不变。</li>
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
                // 终答＝赢家原文，不做二次改写 / 拼接；token 与产物仍按全部子汇总
                TrialTurn turn = mergeTurns(outcomes, pick.winner().turn().getAssistantText());
                if (progress != null) {
                    progress.assistant(turn.getAssistantText());
                }
                return new AggregateOutcome(turn, notices, aggregateLine,
                        indexOfOutcome(outcomes, pick.winner()));
            }
        }
        if (mode == RouterAnswerMode.SUMMARIZE && outcomes.size() >= 1) {
            // 汇总模型与判路由同源：方案里配了就用方案的，否则回落 Agent 的 ROUTER/CHAT 槽
            Optional<Long> parentModel = resolveRouterModel(parent, spec);
            if (parentModel.isPresent()) {
                try {
                    RouterClock.assertBefore(deadlineNanos);
                    if (progress != null) {
                        progress.route("终答汇总中…");
                    }
                    if (trace != null) {
                        // 汇总 LLM 挂在 ROUTE 下，与判路由 LLM 同级兄弟
                        trace.bindParent(routeSpan);
                    }
                    long remainingMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                    EffectiveLimits summarizeLimits = parentLimits.withTimeoutMs(
                            Math.min(parentLimits.timeoutMs(), remainingMs));
                    String summary = react.summarize(
                            MeterScope.console(parent.getId(), parentModel.get(), sessionId, userId),
                            summarizePrompt(parent, userText),
                            summarizeUser(userText, outcomes),
                            summarizeLimits);
                    if (summary != null && !summary.isBlank()) {
                        TrialTurn turn = mergeTurns(outcomes, summary.trim());
                        if (progress != null) {
                            progress.assistant(turn.getAssistantText());
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
                    if (trace != null) {
                        trace.bindParent(null);
                    }
                }
            } else if (progress != null) {
                progress.route("无父模型可汇总，已降级为拼接");
            }
        }
        TrialTurn turn = mergeTurns(outcomes, concat);
        // 多子 / 单子失败 / 曾屏蔽子 assistant：等齐后推终稿；单子 CONCAT 成功时子 ReAct 已流式推过
        boolean needPush = outcomes.size() > 1
                || (outcomes.size() == 1 && outcomes.get(0).error() != null)
                || mode == RouterAnswerMode.SUMMARIZE
                // VOTE 也屏蔽过子正文：降级拼接时必须整段补推一次
                || mode == RouterAnswerMode.VOTE;
        if (progress != null && needPush) {
            progress.assistant(turn.getAssistantText());
        }
        return new AggregateOutcome(turn, notices, aggregateLine, -1);
    }

    /**
     * 判路由 / 汇总模型解析：方案优先，绑根回落 Agent 槽。
     *
     * @param parent 编排根；虚拟根为合成的只读壳，id 为空
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
                    MeterScope.console(parent.getId(), judgeModel.get(), sessionId, userId),
                    votePrompt(parent, userText, pool), voteUserText(userText, pool), voteLimits);
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
     * @param parent   编排根（取平台提示词前缀）
     * @param userText 用户原问
     * @param pool     成功子结果
     * @return 系统提示
     */
    private String votePrompt(AgentDef parent, String userText, List<ChildOutcome> pool) {
        StringBuilder prompt = new StringBuilder();
        // 平台前缀沿用父 ROUTER 的用途模板，避免与判路由 / 汇总两套前缀并存
        String assembled = prompts.build(parent, userText);
        if (!assembled.isBlank()) {
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
            String intent = outcome.intent().candidate().route().getIntent();
            if (intent != null && !intent.isBlank()) {
                prompt.append('：').append(intent.trim());
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
     * 汇总的系统提示：沿用父 ROUTER 用途前缀，再追加「综合成一篇中文答复」的硬要求。
     *
     * @param parent   编排根
     * @param userText 用户原问（模板里的 {{user_query}} 需要值）
     * @return 系统提示
     */
    private String summarizePrompt(AgentDef parent, String userText) {
        StringBuilder prompt = new StringBuilder();
        // ROUTER 用途模板里的 {{user_query}} 需要本轮原问；否则父汇总阶段读不到用户问了什么
        String assembled = prompts.build(parent, userText);
        if (!assembled.isBlank()) {
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
}

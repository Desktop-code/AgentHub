package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.model.LongExtractMark;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.repository.LongExtractSessionGate;
import com.agenthub.memory.domain.service.LongExtractTriggers;
import com.agenthub.memory.domain.service.MemoryCorrectionSignal;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.memory.domain.service.MemoryTexts;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.prompt.application.PromptAssembler;
import com.agenthub.prompt.domain.enums.PromptPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 从对话里抽取可长期成立的用户偏好。所属域：memory。
 * 只在显式记住、会话结束、滚动摘要这三处触发，禁止每轮都抽。
 * 会话级批抽（session_end）成功/失败/跳过均落幂等标记，禁止同一次空闲被 CloseJob 扫两次时重炸出站；
 * 用户再次发言会清掉该标记（见 {@link #clearSessionMarker}），因此「下一次空闲」是允许再抽的。
 * LONG 写入走 {@link LongMemoryMerger}（fact_key 同键合并）。
 * <p>
 * <b>提示词来源</b>：抽取用的系统段只来自平台「记忆抽取模板」的锁定段（抽取契约 + 输出格式），
 * 由 {@link PromptAssembler#assembleLockedByPurpose} 组装；MEMORY 用途槽的 L3 只能作为追加要求。
 * 本类不再内置任何抽取提示词，避免与库里模板出现两份会漂移的文案。
 */
@Service
public class LongExtractService {

    private static final Logger log = LoggerFactory.getLogger(LongExtractService.class);

    private final MemoryModelClient models;
    private final SummaryModelResolver resolver;
    private final ExplicitMemoryStore store;
    private final LongExtractSessionGate gate;
    private final MemoryProperties properties;
    private final PromptAssembler prompts;

    /**
     * @param models     抽取模型调用，会计量和 obs
     * @param resolver   抽取实例解析 + MEMORY L3 追加区
     * @param store      显式通道的独立事务落库
     * @param gate       会话级批抽幂等闸
     * @param properties 批抽总闸 / 最短正文
     * @param prompts    平台锁定段组装（记忆抽取模板）
     */
    public LongExtractService(MemoryModelClient models, SummaryModelResolver resolver,
                              ExplicitMemoryStore store, LongExtractSessionGate gate,
                              MemoryProperties properties, PromptAssembler prompts) {
        this.models = models;
        this.resolver = resolver;
        this.store = store;
        this.gate = gate;
        this.properties = properties;
        this.prompts = prompts;
    }

    /**
     * 显式通道已确认「要写记忆」后的抽取（L0 命中或 L1 判 store）。
     * <p>
     * 本方法不再回看 L0 正则：L1 判 store 的句子本来就没有命中 L0；若在这里再按 L0 短路，
     * L1 的结果会被直接丢掉。开关仍在这里重读一次，兜住排队期间被关掉的策略。
     * <p>
     * <b>线程契约</b>：只允许在 {@code memoryTaskExecutor} 的池线程上调用（由
     * {@link ExplicitMemoryExtractor} 投递）。主请求线程禁止直接调用——这里会同步 {@code block}
     * 最长 60s 等 MEMORY 模型返回，落在首字路径上就是 N6 红线。故本方法刻意不收 public，
     * 跨包也调不到，只能走投递器。
     *
     * @param policy     策略
     * @param agentId    Agent
     * @param userId     用户
     * @param sessionId  会话，只用于计量
     * @param text       用户原文；短指令调用方已用上一轮上下文补全
     */
    void onExplicitConfirmed(MemoryPolicy policy, long agentId, long userId, Long sessionId, String text) {
        onExplicitConfirmed(policy, agentId, userId, sessionId, text, null);
    }

    /**
     * 带纠错信号的显式抽取。纠错 / 确认只影响出站正文首行的提示，不改变写库口径：
     * 覆盖依旧靠 {@link LongMemoryMerger} 的同族合并，模型只负责把「最新结论」抽出来。
     *
     * @param policy     策略
     * @param agentId    Agent
     * @param userId     用户
     * @param sessionId  会话
     * @param text       用户原文（可含【最近会话】段）
     * @param correction 纠错 / 确认信号；null 表示普通显式通道
     */
    void onExplicitConfirmed(MemoryPolicy policy, long agentId, long userId, Long sessionId, String text,
                             MemoryCorrectionSignal.Kind correction) {
        if (!LongExtractTriggers.explicitEnabled(policy)) {
            return;
        }
        try {
            extract(policy, agentId, userId, sessionId, text, "explicit", correction);
        } catch (RuntimeException ex) {
            // 显式记住失败只记日志，不写会话批抽终态，避免误伤后续空闲批抽；
            // 也不想让用户看到「没记住」的错误——记忆是尽力而为，主回答已经正常发出
            log.warn("显式LONG抽取失败 sessionId={} agentId={} type={} msg={}",
                    sessionId, agentId, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    /**
     * 会话结束扫本会话增量。失败只记日志与终态标记，不把已经结束的会话改成失败、不重试出站。
     *
     * @param policy     策略
     * @param agentId    Agent
     * @param userId     用户
     * @param sessionId  会话；非空时走幂等闸
     * @param transcript 本会话正文
     */
    public void onSessionEnd(MemoryPolicy policy, long agentId, long userId, Long sessionId, String transcript) {
        if (sessionId != null && gate.isProcessed(sessionId)) {
            log.info("LONG批抽跳过 reason=already_processed sessionId={} agentId={}", sessionId, agentId);
            return;
        }
        // 平台 YAML 总闸：一键关死 session_end 出站，不影响 explicit
        if (!properties.isLongExtractBatchEnabled()) {
            finish(sessionId, LongExtractMark.SKIPPED, "batch_kill_switch");
            log.info("LONG批抽跳过 reason=batch_kill_switch sessionId={} agentId={}", sessionId, agentId);
            return;
        }
        if (!LongExtractTriggers.sessionEnd(policy)) {
            finish(sessionId, LongExtractMark.SKIPPED, "switch_off");
            log.info("LONG批抽跳过 reason=switch_off sessionId={} agentId={} enableLong={} enableExtract={} onSessionEnd={}",
                    sessionId, agentId,
                    policy == null ? null : policy.getEnableLong(),
                    policy == null ? null : policy.getEnableLongExtract(),
                    policy == null ? null : policy.getLongExtractOnSessionEnd());
            return;
        }
        int minChars = Math.max(0, properties.getLongExtractMinChars());
        String body = transcript == null ? "" : transcript.trim();
        if (body.isEmpty() || body.length() < minChars) {
            finish(sessionId, LongExtractMark.SKIPPED, body.isEmpty() ? "empty_transcript" : "short_transcript");
            log.info("LONG批抽跳过 reason={} sessionId={} agentId={} chars={} minChars={}",
                    body.isEmpty() ? "empty_transcript" : "short_transcript",
                    sessionId, agentId, body.length(), minChars);
            return;
        }
        try {
            extract(policy, agentId, userId, sessionId, body, "session_end");
            finish(sessionId, LongExtractMark.DONE, "ok");
        } catch (BizException ex) {
            // 无可用模型 / Key 等业务失败：终态 FAILED，禁止下一分钟重炸
            finish(sessionId, LongExtractMark.FAILED, reasonOf(ex));
            log.warn("LONG批抽失败终态 sessionId={} agentId={} type=BizException msg={}",
                    sessionId, agentId, ex.getMessage());
        } catch (RuntimeException ex) {
            finish(sessionId, LongExtractMark.FAILED, reasonOf(ex));
            log.warn("LONG批抽失败终态 sessionId={} agentId={} type={} msg={}",
                    sessionId, agentId, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    /**
     * 滚动摘要前对即将移出的旧段顺带抽一次。失败不影响摘要本身；受批抽总闸约束。
     *
     * @param policy    策略
     * @param agentId   Agent
     * @param userId    用户
     * @param sessionId 会话
     * @param head      即将摘要掉的旧轮
     */
    public void onSummary(MemoryPolicy policy, long agentId, long userId, Long sessionId, List<ContextTurn> head) {
        if (!properties.isLongExtractBatchEnabled() || !LongExtractTriggers.onSummary(policy)) {
            return;
        }
        try {
            extract(policy, agentId, userId, sessionId, MemoryTexts.renderTurns(head), "summary");
        } catch (RuntimeException ex) {
            log.warn("滚动摘要顺带抽取失败 sessionId={} type={}", sessionId, ex.getClass().getSimpleName());
        }
    }

    /**
     * 清空会话级批抽标记，让同一 session 行之后可以再抽一次。所属域：memory。
     * <p>
     * 调用方两处：
     * <ol>
     *   <li>用户在本会话再次发言（{@code TrialMemoryCoordinator.prepareUser}）：
     *       上一轮空闲批抽的 DONE / FAILED / SKIPPED 都作废，本次聊完再次空闲时可再抽；</li>
     *   <li>清空会话消息（{@code TrialMemoryCoordinator.invalidate}）：消息都没了，旧标记不该留。</li>
     * </ol>
     * 只删这一行，不改状态枚举；同一次空闲期间没有用户轮，因此仍靠已有终态防重。
     *
     * @param sessionId 会话
     */
    public void clearSessionMarker(long sessionId) {
        try {
            gate.clear(sessionId);
        } catch (RuntimeException ex) {
            log.warn("清除LONG批抽标记失败 sessionId={} type={}", sessionId, ex.getClass().getSimpleName());
        }
    }

    private void extract(MemoryPolicy policy, long agentId, long userId, Long sessionId, String source, String trigger) {
        extract(policy, agentId, userId, sessionId, source, trigger, null);
    }

    /**
     * 单次抽取主流程。
     * <p>
     * <b>两处与「纠错」相关的口径</b>：
     * <ol>
     *   <li>批抽（session_end / summary）正文里含助手轮，出站前剔除助手纠错独白
     *       （{@link MemoryCorrectionSignal#stripAssistantCorrections}）：助手单方面说
     *       「你是想说苹果吗」不构成用户偏好，绝不能写成记忆；</li>
     *   <li>显式通道带纠错信号时，在正文首行加提示，让模型复用原事实的 fact_key
     *       （不改平台锁定模板，模板里已有同类约束）。</li>
     * </ol>
     *
     * @param policy     策略
     * @param agentId    Agent
     * @param userId     用户
     * @param sessionId  会话
     * @param source     待抽取正文
     * @param trigger    触发来源（explicit / session_end / summary），用于日志与计量
     * @param correction 纠错 / 确认信号；null 表示无
     */
    private void extract(MemoryPolicy policy, long agentId, long userId, Long sessionId, String source,
                         String trigger, MemoryCorrectionSignal.Kind correction) {
        if (source == null || source.isBlank()) {
            return;
        }
        // 批抽正文里助手轮是明文拼进去的：先剔掉助手纠错独白，避免把模型的猜测当用户偏好写库。
        // 显式通道不清洗——那里正需要助手上一轮的追问作为「确认」的上下文。
        if (isBatchTrigger(trigger)) {
            String cleaned = MemoryCorrectionSignal.stripAssistantCorrections(source);
            if (!cleaned.equals(source)) {
                source = cleaned;
                if (source.isBlank()) {
                    // 整段只有助手纠错：没有任何用户证据，宁可不写
                    log.info("LONG批抽跳过 reason=assistant_correction_only trigger={} sessionId={}",
                            trigger, sessionId);
                    return;
                }
            }
        }
        // 纠错 / 确认提示只加在正文首行；系统段仍是平台锁定模板，不在这里拼提示词
        String hint = MemoryCorrectionSignal.hint(correction);
        if (!hint.isEmpty()) {
            source = hint + source;
        }
        long modelId;
        try {
            modelId = resolver.requireExtract(policy, agentId);
        } catch (BizException ex) {
            // 无可用实例：对 session_end 由调用方记 FAILED；此处原样抛出
            throw ex;
        }
        // 系统段只来自平台 MEMORY 模板的锁定段；MEMORY 槽的 L3 只能追加在锁定段之后，
        // 不能替换抽取契约/输出格式，旧版 key/value/confidence 覆盖值也不参与组装。
        String extra = resolver.memoryPromptExtra(agentId);
        String system = prompts.assembleLockedByPurpose(PromptPurpose.MEMORY.name(), null, extra);
        // 出站前落一行目标信息：实例名 / 模型 / 用途 / 脱敏地址，便于区分「没调用」与「空回」
        MemoryOutboundLogs.start(log, trigger, agentId, sessionId, userId, modelId,
                models.describe(modelId), null, null, system.length(), source.length());
        String raw = models.complete(agentId, sessionId, userId, modelId, system, source);
        List<MemoryTexts.PreferenceDraft> drafts = MemoryTexts.parsePreferences(raw);
        int rawChars = raw == null ? 0 : raw.length();
        if (drafts.isEmpty()) {
            if (MemoryTexts.isEmptyExtractPayload(raw)) {
                // 空数组：视为本轮已处理完毕，不再重试出站（由 onSessionEnd 记 DONE）
                log.info("LONG批抽无条目 trigger={} sessionId={} modelId={} reason=model_empty_array rawChars={}",
                        trigger, sessionId, modelId, rawChars);
            } else {
                log.warn("LONG批抽解析为空 trigger={} sessionId={} modelId={} rawChars={} preview={}",
                        trigger, sessionId, modelId, rawChars, preview(raw, 400));
            }
            return;
        }
        // 写库走 REQUIRES_NEW 的独立事务：异步线程不与请求线程事务合并，模型出站不在事务内
        // 先把模型判定结果打成一行（只给 fact_key/ttl/importance，不写正文），
        // 排查「说了记住但库里没变」时，能立刻区分是模型没抽、抽了别的 key，还是抽了但合并失败
        log.info("LONG批抽条目 trigger={} sessionId={} modelId={} items={}",
                trigger, sessionId, modelId, brief(drafts));
        ExplicitMemoryStore.Outcome outcome = store.store(agentId, userId, drafts,
                policy.getMaxLongItems(), userId);
        log.info("LONG批抽完成 trigger={} sessionId={} agentId={} userId={} modelId={} parsed={} inserted={} updated={} skippedSecret={} skippedFactKey={}",
                trigger, sessionId, agentId, userId, modelId, drafts.size(),
                outcome.inserted(), outcome.updated(), outcome.skippedSecret(), outcome.skippedFactKey());
    }

    /**
     * 是否批抽来源。批抽正文含助手轮，需要剔除助手纠错独白；显式通道不做这个清洗。
     *
     * @param trigger 触发来源
     * @return true 表示 session_end / summary
     */
    private static boolean isBatchTrigger(String trigger) {
        return "session_end".equals(trigger) || "summary".equals(trigger);
    }

    private void finish(Long sessionId, LongExtractMark mark, String reason) {
        if (sessionId == null || mark == null) {
            return;
        }
        try {
            gate.complete(sessionId, mark, reason);
        } catch (RuntimeException ex) {
            log.warn("写入LONG批抽标记失败 sessionId={} mark={} type={}",
                    sessionId, mark, ex.getClass().getSimpleName());
        }
    }

    private static String reasonOf(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String type = ex.getClass().getSimpleName();
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return type;
        }
        // 截断并去掉换行，避免原因列膨胀；禁止把密钥写进 reason（上游也不应传入）
        String one = msg.replace('\n', ' ').replace('\r', ' ').trim();
        if (one.length() > 80) {
            one = one.substring(0, 80);
        }
        return type + ":" + one;
    }

    private static String preview(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String oneLine = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() <= max) {
            return oneLine;
        }
        return oneLine.substring(0, max) + "...";
    }

    /**
     * 抽取结果的日志摘要：只列 fact_key / ttl_days / importance，<b>不写正文</b>。
     *
     * <p>用途：判断「用户说了记住但库里没变」到底是哪种情况 ——
     * 模型没抽（这行不出现）、抽了别的 key（能看到 key 名变化）、
     * 还是抽对了但 ttl 判断不同（能看到 ttl_days）。
     *
     * @param drafts 解析结果
     * @return 形如 {@code [health_status ttl=0 imp=80, dietary_restriction ttl=7 imp=60]}
     */
    private static String brief(List<MemoryTexts.PreferenceDraft> drafts) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < drafts.size(); i++) {
            MemoryTexts.PreferenceDraft draft = drafts.get(i);
            if (i > 0) {
                text.append(", ");
            }
            text.append(draft.getFactKey() == null ? "-" : draft.getFactKey())
                    .append(" ttl=").append(draft.getTtlDays())
                    .append(" imp=").append(draft.getImportance());
        }
        return text.append(']').toString();
    }
}

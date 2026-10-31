package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.model.LongExtractMark;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.MemoryScope;
import com.agenthub.memory.domain.repository.LongExtractSessionGate;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.LongExtractTriggers;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.memory.domain.service.MemoryTexts;
import com.agenthub.memory.infra.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 从对话里抽取可长期成立的用户偏好。所属域：memory。
 * 只在显式记住、会话结束、滚动摘要这三处触发，禁止每轮都抽。
 * 会话级批抽（session_end）成功/失败/跳过均落幂等标记，禁止 CloseJob 对同一 session 重炸出站。
 * LONG 写入走 {@link LongMemoryMerger}（fact_key 同键合并）。
 */
@Service
public class LongExtractService {

    private static final Logger log = LoggerFactory.getLogger(LongExtractService.class);
    private static final String INSTRUCTION = """
            只输出一个 JSON 数组。禁止输出分析、解释、Markdown 或其它文字。
            每项必须含：fact_key、title、content、importance（0-100 整数）。
            fact_key 为小写 snake_case（仅 [a-z0-9_]，≤64）；同一事实必须同一 key，禁止近义乱造。
            常用键示例：residence_city（居住/常驻城市）、dietary_restriction（饮食禁忌）、language_preference（语言偏好）、timezone、occupation。
            应抽取：居住/常驻城市、用户自称的工作或通勤城市、回答格式/语言偏好、明确禁忌。
            不要抽取：一次性行程、当次天气查询、工具原文、密钥、会话临时结论。
            缺 fact_key 的条目会被丢弃。没有可长期成立的条目时，只输出：[]
            """;

    private final MemoryModelClient models;
    private final SummaryModelResolver resolver;
    private final MemoryItemRepository items;
    private final LongMemoryMerger merger;
    private final LongExtractSessionGate gate;
    private final MemoryProperties properties;

    /**
     * @param models     抽取模型调用，会计量和 obs
     * @param resolver   抽取实例解析
     * @param items      超限淘汰
     * @param merger     LONG 同键合并
     * @param gate       会话级批抽幂等闸
     * @param properties 批抽总闸 / 最短正文
     */
    public LongExtractService(MemoryModelClient models, SummaryModelResolver resolver, MemoryItemRepository items,
                              LongMemoryMerger merger, LongExtractSessionGate gate, MemoryProperties properties) {
        this.models = models;
        this.resolver = resolver;
        this.items = items;
        this.merger = merger;
        this.gate = gate;
        this.properties = properties;
    }

    /**
     * 用户明确说记住时当轮抽取。开关没开则什么都不做。
     * 不受批抽总闸 / 会话幂等标记误伤；仍受 enable_long + long_extract_on_explicit。
     *
     * @param policy    策略
     * @param agentId   Agent
     * @param userId    用户
     * @param sessionId 会话，只用于计量
     * @param text      用户原文
     */
    public void onExplicit(MemoryPolicy policy, long agentId, long userId, Long sessionId, String text) {
        if (!LongExtractTriggers.explicit(policy, text)) {
            return;
        }
        try {
            extract(policy, agentId, userId, sessionId, text, "explicit");
        } catch (RuntimeException ex) {
            // 显式记住失败只记日志，不写会话批抽终态，避免误伤后续空闲批抽
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
     * 清空会话消息时丢掉批抽标记，同一 session 行之后可再批抽。
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
        if (source == null || source.isBlank()) {
            return;
        }
        long modelId;
        try {
            modelId = resolver.requireExtract(policy, agentId);
        } catch (BizException ex) {
            // 无可用实例：对 session_end 由调用方记 FAILED；此处原样抛出
            throw ex;
        }
        log.info("LONG批抽开始 trigger={} sessionId={} agentId={} userId={} modelId={} sourceChars={}",
                trigger, sessionId, agentId, userId, modelId, source.length());
        String raw = models.complete(agentId, sessionId, userId, modelId, INSTRUCTION, source);
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
        int inserted = 0;
        int updated = 0;
        int skippedSecret = 0;
        int skippedFactKey = 0;
        for (MemoryTexts.PreferenceDraft draft : drafts) {
            try {
                MemoryTexts.rejectSecret(draft.getContent());
            } catch (RuntimeException ex) {
                skippedSecret++;
                continue;
            }
            Optional<Boolean> outcome = merger.upsertExtracted(agentId, userId, draft, userId);
            if (outcome.isEmpty()) {
                skippedFactKey++;
            } else if (Boolean.TRUE.equals(outcome.get())) {
                inserted++;
            } else {
                updated++;
            }
        }
        items.evictOverflow(agentId, userId, MemoryScope.LONG.name(), policy.getMaxLongItems(), userId);
        log.info("LONG批抽完成 trigger={} sessionId={} agentId={} userId={} modelId={} parsed={} inserted={} updated={} skippedSecret={} skippedFactKey={}",
                trigger, sessionId, agentId, userId, modelId, drafts.size(), inserted, updated, skippedSecret, skippedFactKey);
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
}

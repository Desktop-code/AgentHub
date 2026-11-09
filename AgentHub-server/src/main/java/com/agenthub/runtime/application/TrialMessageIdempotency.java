package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.domain.model.TrialIdemGuard;
import com.agenthub.runtime.domain.model.TrialIdempotencyRecord;
import com.agenthub.runtime.domain.repository.RunIdempotencyRepository;
import com.agenthub.runtime.domain.service.TrialIdemStore;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.infra.idempotency.TrialIdempotencyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 「发消息」请求级幂等。所属域：runtime。
 * <p>
 * 作用面：控制台试跑/运行的发送主写路径——{@code TrialRunAppService#send} 与
 * {@code TrialRunAppService#stream}（含 SSE）。客户端用 Header {@code Idempotency-Key} 传入业务幂等键。
 * <p>
 * 契约（写死）：
 * <ul>
 *   <li>同会话 + 同键 + 同内容：第二次及以后**不再**插用户消息、不再调模型/工具、不再写计费流水，
 *       直接返回首次结果的等价响应（{@code MessageResp.replayed=true}，事件为空，客户端应回拉
 *       {@code GET /sessions/{id}/events} 或等进度快照）；</li>
 *   <li>同键但内容指纹不同：409（{@link ErrorCode#IDEMPOTENCY_CONFLICT}），禁止静默覆盖；</li>
 *   <li>同键但首轮仍在跑：409 明确告知「正在处理中」，禁止第二路并发；</li>
 *   <li>无键 / 开关关闭：完全保持改造前行为，直发；</li>
 *   <li>Redis 不可用：**不放行双跑**，落 DB 唯一键 {@code (session_id, idem_key)} 判定。</li>
 * </ul>
 * 状态机与陈旧接管见 {@link TrialIdempotencyRecord}。失败同样是终态：同键重试返回同一失败，
 * 绝不允许「幂等发现失败就再跑一遍模型」。
 */
@Service
public class TrialMessageIdempotency {

    /** 客户端幂等键请求头。与常见网关一致，写死 header 形态，不再支持 body 字段 */
    public static final String HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(TrialMessageIdempotency.class);

    /**
     * 键格式：字母数字下划线短横，8~64 位。
     * 限制字符集是为了让键安全地直接进 Redis key；长度按 UUID（36）与常见雪花串留余量。
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final TrialIdemStore store;
    private final RunIdempotencyRepository repository;
    private final TrialIdempotencyProperties properties;

    /**
     * @param store      Redis 快路径
     * @param repository DB 唯一键兜底
     * @param properties 开关 / TTL / 陈旧窗口
     */
    public TrialMessageIdempotency(TrialIdemStore store, RunIdempotencyRepository repository,
                                   TrialIdempotencyProperties properties) {
        this.store = store;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 请求进入幂等闸门。
     *
     * @param sessionId 会话（已通过归属校验）
     * @param rawKey    请求头里的幂等键，可空
     * @param content   用户正文，用于计算内容指纹
     * @param images    随轮图片，可空；参与指纹
     * @return 未托管 / 已占位 / 命中重放
     * @throws BizException 键格式非法、键被不同内容占用，或首轮仍在执行
     */
    public TrialIdemGuard begin(Long sessionId, String rawKey, String content, List<String> images) {
        if (!properties.isEnabled() || rawKey == null || rawKey.isBlank()) {
            return TrialIdemGuard.none();
        }
        String key = rawKey.trim();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "幂等键格式不正确：只允许字母、数字、下划线和短横，长度 8~64");
        }
        String fingerprint = fingerprint(content, images);

        // 1) 快路径：Redis 已有同键记录，直接判定（终态重放 / 内容冲突 / 正在执行）
        Optional<TrialIdempotencyRecord> cached = store.get(sessionId, key);
        if (cached.isPresent()) {
            return decide(sessionId, key, fingerprint, cached.get());
        }

        // 2) Redis 原子占位：跨实例把并发请求收成一次
        TrialIdempotencyRecord running = runningRecord(sessionId, key, fingerprint);
        Boolean claimed = store.putIfAbsent(running, ttl());
        if (Boolean.FALSE.equals(claimed)) {
            // 占位失败说明别人先到：读回它的记录再判定。读不到（刚好过期）不能当成功，继续走 DB
            Optional<TrialIdempotencyRecord> raced = store.get(sessionId, key);
            if (raced.isPresent()) {
                return decide(sessionId, key, fingerprint, raced.get());
            }
        }

        // 3) DB 唯一键：跨实例最终兜底。Redis 不可用（claimed == null）时这是唯一判定依据
        boolean inserted;
        try {
            inserted = repository.insert(running);
        } catch (RuntimeException ex) {
            // DB 侧真的炸了（连接/权限）：把刚占的 Redis 位放掉，别让这个键被占死到 TTL
            store.delete(sessionId, key);
            throw ex;
        }
        if (!inserted) {
            TrialIdempotencyRecord existing = repository.find(sessionId, key).orElse(null);
            if (existing == null) {
                // 唯一冲突却读不到（并发删除的极窄窗口）：拒绝并让客户端重试，绝不放行第二轮
                throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, "该消息的幂等记录正在切换，请稍后重试");
            }
            return decide(sessionId, key, fingerprint, existing);
        }
        return TrialIdemGuard.managed(sessionId, key, fingerprint);
    }

    /**
     * 本轮有结论后回写终态。成功与业务失败都算结论，都必须落状态，否则同键重试会被判成「进行中」。
     *
     * @param guard 已占位的守卫；未托管时直接返回
     * @param resp  本轮响应
     */
    public void complete(TrialIdemGuard guard, MessageResp resp) {
        if (guard == null || !guard.managed() || resp == null) {
            return;
        }
        TrialIdempotencyRecord record = runningRecord(guard.sessionId(), guard.idemKey(), guard.fingerprint());
        boolean failed = resp.getStatus() != null && "FAILED".equalsIgnoreCase(resp.getStatus());
        record.setState(failed ? TrialIdempotencyRecord.State.FAILED : TrialIdempotencyRecord.State.SUCCESS);
        record.setRunStatus(resp.getStatus());
        record.setAssistantPreview(TrialTexts.preview(resp.getAssistantPreview()));
        record.setErrorSummary(TrialTexts.summary(resp.getErrorSummary()));
        record.setPlanPending(resp.isPlanPending());
        try {
            repository.updateResult(record);
        } catch (RuntimeException ex) {
            // 状态没写进去只会让同键重试被判「进行中」直到陈旧接管；不影响本轮已完成的业务
            log.warn("幂等状态回写失败 sessionId={} type={}", guard.sessionId(), ex.getClass().getSimpleName());
        }
        store.put(record, ttl());
    }

    /**
     * 执行前失败（会话正忙、图片非法等）：删掉占位，允许客户端用同一键重试。
     * <p>
     * 只应在**没产生任何业务结果**时调用；已经跑过模型的失败必须走 {@link #complete}，
     * 否则重试会再跑一轮模型。
     *
     * @param guard 已占位的守卫
     */
    public void release(TrialIdemGuard guard) {
        if (guard == null || !guard.managed()) {
            return;
        }
        try {
            repository.delete(guard.sessionId(), guard.idemKey());
        } catch (RuntimeException ex) {
            log.warn("幂等占位删除失败 sessionId={} type={}", guard.sessionId(), ex.getClass().getSimpleName());
        }
        store.delete(guard.sessionId(), guard.idemKey());
    }

    /**
     * 根据已有记录给出结论。
     *
     * @param sessionId   会话
     * @param key         幂等键
     * @param fingerprint 本次请求指纹
     * @param record      已存在记录
     * @return 已占位（陈旧接管成功）或命中重放
     */
    private TrialIdemGuard decide(Long sessionId, String key, String fingerprint, TrialIdempotencyRecord record) {
        // 键相同但内容不同：明确 4xx，绝不静默覆盖别人的结果
        if (record.getFingerprint() != null && !record.getFingerprint().equals(fingerprint)) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, "该幂等键已用于不同的消息内容，请更换幂等键");
        }
        if (record.finished()) {
            // 以 DB 为准刷新 Redis 副本，避免 Redis 里残留非终态
            store.put(record, ttl());
            return TrialIdemGuard.replay(replayResponse(record));
        }
        // 仍是 RUNNING：仅在陈旧窗口外允许接管，避免进程崩溃把键永久占死
        if (record.staleAt(System.currentTimeMillis(), properties.getStaleSec())) {
            TrialIdempotencyRecord takeover = runningRecord(sessionId, key, fingerprint);
            if (repository.takeOver(takeover)) {
                store.put(takeover, ttl());
                return TrialIdemGuard.managed(sessionId, key, fingerprint);
            }
            // 接管失败：可能是别人刚好收尾成终态，读回再判一次
            Optional<TrialIdempotencyRecord> latest = repository.find(sessionId, key);
            if (latest.isPresent() && latest.get().finished()) {
                return TrialIdemGuard.replay(replayResponse(latest.get()));
            }
        }
        throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, "该消息正在处理中，请稍后重试（请沿用同一个幂等键）");
    }

    /**
     * 已存记录 → 等价响应。不带事件：整轮事件已在库里，客户端按 sessionId 回拉时间线即可。
     *
     * @param record 终态记录
     * @return 重放响应
     */
    private MessageResp replayResponse(TrialIdempotencyRecord record) {
        MessageResp resp = new MessageResp();
        resp.setSessionId(record.getSessionId());
        resp.setStatus(record.getRunStatus() == null ? "SUCCESS" : record.getRunStatus());
        resp.setAssistantPreview(record.getAssistantPreview() == null ? "" : record.getAssistantPreview());
        resp.setErrorSummary(record.getErrorSummary());
        resp.setPlanPending(record.isPlanPending());
        resp.setReplayed(true);
        return resp;
    }

    /**
     * @param sessionId   会话
     * @param key         幂等键
     * @param fingerprint 内容指纹
     * @return 新的 RUNNING 记录
     */
    private TrialIdempotencyRecord runningRecord(Long sessionId, String key, String fingerprint) {
        TrialIdempotencyRecord record = new TrialIdempotencyRecord();
        record.setSessionId(sessionId);
        record.setIdemKey(key);
        record.setFingerprint(fingerprint);
        record.setState(TrialIdempotencyRecord.State.RUNNING);
        record.setUpdatedAtMs(System.currentTimeMillis());
        return record;
    }

    private Duration ttl() {
        int sec = properties.getTtlSec();
        return Duration.ofSeconds(sec > 0 ? sec : 1800);
    }

    /**
     * 内容指纹：正文 + 每张图的长度前缀，避免「正文和图片拼接歧义」造成不同请求指纹相同。
     *
     * @param content 正文
     * @param images  图片，可空
     * @return SHA-256 小写十六进制
     */
    static String fingerprint(String content, List<String> images) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String body = content == null ? "" : content;
            digest.update((body.length() + ":").getBytes(StandardCharsets.UTF_8));
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            if (images != null) {
                for (String image : images) {
                    String one = image == null ? "" : image;
                    digest.update((one.length() + ":").getBytes(StandardCharsets.UTF_8));
                    digest.update(one.getBytes(StandardCharsets.UTF_8));
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法；真的缺失时抛内部错误，绝不能退化成「不做幂等」
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", ex);
        }
    }
}

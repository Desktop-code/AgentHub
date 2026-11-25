package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.domain.model.TrialIdemGuard;
import com.agenthub.runtime.domain.model.TrialIdempotencyRecord;
import com.agenthub.runtime.domain.repository.RunIdempotencyRepository;
import com.agenthub.runtime.domain.service.TrialIdemStore;
import com.agenthub.runtime.infra.idempotency.TrialIdempotencyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发消息幂等（I1）。所属域：runtime。
 * <p>
 * 覆盖写死的五条契约：同键同内容只跑一次且返回等价结果；同键异内容明确 4xx；
 * 进行中拒绝并发；无键 / 关开关保持现网行为；Redis 不可用不得双跑（落 DB 唯一键判定）。
 * 不连库、不连 Redis、不出站模型。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrialMessageIdempotencyTest {

    private static final long SESSION_ID = 7L;
    private static final String KEY = "idem-key-0001";
    private static final String CONTENT = "帮我看下这个月的报销";

    @Mock
    private TrialIdemStore store;
    @Mock
    private RunIdempotencyRepository repository;

    private TrialIdempotencyProperties properties;
    private TrialMessageIdempotency idempotency;

    @BeforeEach
    void setUp() {
        properties = new TrialIdempotencyProperties();
        properties.setEnabled(true);
        properties.setTtlSec(1800);
        properties.setStaleSec(600);
        idempotency = new TrialMessageIdempotency(store, repository, properties);
        when(store.get(anyLong(), any())).thenReturn(Optional.empty());
    }

    @Test
    void noKeyKeepsCurrentBehaviour() {
        TrialIdemGuard guard = idempotency.begin(SESSION_ID, null, CONTENT, null);
        assertFalse(guard.managed());
        assertFalse(guard.hasReplay());
        // 无键绝不触碰幂等存储，改造前行为零差异
        verify(store, never()).putIfAbsent(any(), any());
        verify(repository, never()).insert(any());
    }

    @Test
    void blankKeyKeepsCurrentBehaviour() {
        assertFalse(idempotency.begin(SESSION_ID, "   ", CONTENT, null).managed());
        verify(repository, never()).insert(any());
    }

    @Test
    void disabledSwitchKeepsCurrentBehaviour() {
        properties.setEnabled(false);
        assertFalse(idempotency.begin(SESSION_ID, KEY, CONTENT, null).managed());
        verify(repository, never()).insert(any());
    }

    @Test
    void invalidKeyIsRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> idempotency.begin(SESSION_ID, "带中文的键", CONTENT, null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void sameKeySameContentReplaysWithoutInsert() {
        TrialIdempotencyRecord done = finished(TrialIdempotencyRecord.State.SUCCESS);
        when(store.get(SESSION_ID, KEY)).thenReturn(Optional.of(done));

        TrialIdemGuard guard = idempotency.begin(SESSION_ID, KEY, CONTENT, null);

        assertTrue(guard.hasReplay());
        MessageResp resp = guard.replay();
        assertEquals(SESSION_ID, resp.getSessionId());
        assertEquals("SUCCESS", resp.getStatus());
        assertTrue(resp.isReplayed());
        // 重放不再占位、不再执行
        verify(repository, never()).insert(any());
    }

    @Test
    void sameKeyDifferentContentIsRejected() {
        TrialIdempotencyRecord done = finished(TrialIdempotencyRecord.State.SUCCESS);
        done.setFingerprint("不是这次的指纹");
        when(store.get(SESSION_ID, KEY)).thenReturn(Optional.of(done));

        BizException ex = assertThrows(BizException.class,
                () -> idempotency.begin(SESSION_ID, KEY, CONTENT, null));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不同的消息内容"));
    }

    @Test
    void imagesChangeFingerprint() {
        TrialIdempotencyRecord done = finished(TrialIdempotencyRecord.State.SUCCESS);
        when(store.get(SESSION_ID, KEY)).thenReturn(Optional.of(done));

        // 不带图能命中，带图必须判成不同内容
        assertTrue(idempotency.begin(SESSION_ID, KEY, CONTENT, null).hasReplay());
        assertThrows(BizException.class,
                () -> idempotency.begin(SESSION_ID, KEY, CONTENT, List.of("data:image/png;base64,AAA")));
    }

    @Test
    void runningRequestRejectsSecondCaller() {
        TrialIdempotencyRecord running = running(TrialIdempotencyRecord.State.RUNNING);
        running.setUpdatedAtMs(System.currentTimeMillis());
        when(store.get(SESSION_ID, KEY)).thenReturn(Optional.of(running));

        BizException ex = assertThrows(BizException.class,
                () -> idempotency.begin(SESSION_ID, KEY, CONTENT, null));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("正在处理中"));
    }

    @Test
    void staleRunningCanBeTakenOver() {
        TrialIdempotencyRecord stale = running(TrialIdempotencyRecord.State.RUNNING);
        // 一小时前占位、进程已崩溃：必须允许接手，否则该键被永久占死
        stale.setUpdatedAtMs(System.currentTimeMillis() - 3_600_000L);
        when(store.get(SESSION_ID, KEY)).thenReturn(Optional.of(stale));
        when(repository.takeOver(any())).thenReturn(true);

        TrialIdemGuard guard = idempotency.begin(SESSION_ID, KEY, CONTENT, null);

        assertTrue(guard.managed());
        verify(repository).takeOver(any());
    }

    @Test
    void redisDownFallsBackToDatabaseClaim() {
        // Redis 不可用：putIfAbsent 返回 null，必须落 DB 唯一键，而不是「当没键」
        when(store.putIfAbsent(any(), any())).thenReturn(null);
        when(repository.insert(any())).thenReturn(true);

        TrialIdemGuard guard = idempotency.begin(SESSION_ID, KEY, CONTENT, null);

        assertTrue(guard.managed());
        verify(repository).insert(any());
        assertEquals(KEY, guard.idemKey());
    }

    @Test
    void redisDownAndDatabaseDuplicateReplays() {
        when(store.putIfAbsent(any(), any())).thenReturn(null);
        when(repository.insert(any())).thenReturn(false);
        when(repository.find(SESSION_ID, KEY)).thenReturn(Optional.of(finished(TrialIdempotencyRecord.State.SUCCESS)));

        TrialIdemGuard guard = idempotency.begin(SESSION_ID, KEY, CONTENT, null);

        assertTrue(guard.hasReplay());
        assertEquals("SUCCESS", guard.replay().getStatus());
    }

    @Test
    void redisDownAndDatabaseDuplicateWithoutRowIsRejected() {
        when(store.putIfAbsent(any(), any())).thenReturn(null);
        when(repository.insert(any())).thenReturn(false);
        when(repository.find(SESSION_ID, KEY)).thenReturn(Optional.empty());

        // 唯一冲突却读不回来：宁可让客户端重试，也绝不放行第二轮
        BizException ex = assertThrows(BizException.class,
                () -> idempotency.begin(SESSION_ID, KEY, CONTENT, null));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.getErrorCode());
    }

    @Test
    void completeWritesFailedTerminalState() {
        TrialIdemGuard guard = TrialIdemGuard.managed(SESSION_ID, KEY,
                TrialMessageIdempotency.fingerprint(CONTENT, null));
        MessageResp failed = new MessageResp();
        failed.setSessionId(SESSION_ID);
        failed.setStatus("FAILED");
        failed.setErrorSummary("模型调用失败");

        idempotency.complete(guard, failed);

        verify(repository).updateResult(org.mockito.ArgumentMatchers.argThat(
                (TrialIdempotencyRecord record) -> record.getState() == TrialIdempotencyRecord.State.FAILED
                        && "FAILED".equals(record.getRunStatus())));
        verify(store).put(any(), any(Duration.class));
    }

    @Test
    void completeSkipsUnmanagedGuard() {
        MessageResp resp = new MessageResp();
        resp.setStatus("SUCCESS");
        idempotency.complete(TrialIdemGuard.none(), resp);
        verify(repository, never()).updateResult(any());
        verify(store, never()).put(any(), any(Duration.class));
    }

    @Test
    void releaseDeletesClaimSoRetryCanRun() {
        TrialIdemGuard guard = TrialIdemGuard.managed(SESSION_ID, KEY,
                TrialMessageIdempotency.fingerprint(CONTENT, null));
        idempotency.release(guard);
        verify(repository).delete(SESSION_ID, KEY);
        verify(store).delete(SESSION_ID, KEY);
    }

    @Test
    void fingerprintIsStableAndContentSensitive() {
        assertEquals(TrialMessageIdempotency.fingerprint(CONTENT, null),
                TrialMessageIdempotency.fingerprint(CONTENT, null));
        assertFalse(TrialMessageIdempotency.fingerprint(CONTENT, null)
                .equals(TrialMessageIdempotency.fingerprint(CONTENT + " ", null)));
    }

    @Test
    void replayResponseCarriesStoredFields() {
        TrialIdempotencyRecord done = finished(TrialIdempotencyRecord.State.FAILED);
        done.setAssistantPreview("已生成计划");
        done.setErrorSummary("上游超时");
        done.setPlanPending(true);
        when(store.get(eq(SESSION_ID), eq(KEY))).thenReturn(Optional.of(done));

        MessageResp resp = idempotency.begin(SESSION_ID, KEY, CONTENT, null).replay();

        assertNotNull(resp);
        assertEquals("FAILED", resp.getStatus());
        assertEquals("已生成计划", resp.getAssistantPreview());
        assertEquals("上游超时", resp.getErrorSummary());
        assertTrue(resp.isPlanPending());
        assertTrue(resp.getEvents().isEmpty());
    }

    /**
     * @return 指纹与当前用例正文一致的 RUNNING 记录
     */
    private TrialIdempotencyRecord running(TrialIdempotencyRecord.State state) {
        TrialIdempotencyRecord record = new TrialIdempotencyRecord();
        record.setSessionId(SESSION_ID);
        record.setIdemKey(KEY);
        record.setFingerprint(TrialMessageIdempotency.fingerprint(CONTENT, null));
        record.setState(state);
        record.setUpdatedAtMs(System.currentTimeMillis());
        return record;
    }

    /**
     * @return 指纹与当前用例正文一致的终态记录
     */
    private TrialIdempotencyRecord finished(TrialIdempotencyRecord.State state) {
        TrialIdempotencyRecord record = running(state);
        record.setRunStatus(state == TrialIdempotencyRecord.State.FAILED ? "FAILED" : "SUCCESS");
        record.setUpdatedAtMs(System.currentTimeMillis());
        return record;
    }
}

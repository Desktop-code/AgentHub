package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.model.LongExtractMark;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.repository.LongExtractSessionGate;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.FactKeyNormalizer;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.memory.infra.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话批抽「可再抽」：幂等只挡同一次空闲，用户再次发言清标记后可以再抽。
 * 所属域：memory。
 * <p>
 * 用内存版 {@link LongExtractSessionGate} 代替 MySQL 行，端到端验证：
 * 新会话首次空闲 → DONE；同一空闲重复扫 → already_processed 不再出站；
 * 用户再发言（clear）→ 表中无行；再次空闲 → 再抽一次。
 */
@ExtendWith(MockitoExtension.class)
class LongExtractReExtractTest {

    private static final long AGENT_ID = 4L;
    private static final long USER_ID = 7L;
    private static final long SESSION_ID = 3L;
    private static final String BODY = "用户：我住广州，以后查天气默认广州。\n助手：好的，记住了。\n";
    private static final String EXTRACT_JSON =
            "[{\"fact_key\":\"residence_city\",\"title\":\"城市\",\"content\":\"广州\",\"importance\":80}]";

    @Mock
    private MemoryModelClient models;
    @Mock
    private SummaryModelResolver resolver;
    @Mock
    private MemoryItemRepository items;
    @Mock
    private com.agenthub.prompt.application.PromptAssembler prompts;
    @Mock
    private MemoryVectorHook vectors;

    private MemoryProperties properties;
    private InMemoryGate gate;
    private LongExtractService extracts;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setLongExtractBatchEnabled(true);
        properties.setLongExtractMinChars(8);
        gate = new InMemoryGate();
        LongMemoryMerger merger = new LongMemoryMerger(items, new FactKeyNormalizer(Map.of()), vectors);
        ExplicitMemoryStore store = new ExplicitMemoryStore(merger, items, vectors);
        extracts = new LongExtractService(models, resolver, store, gate, properties, prompts);
        // 总闸关 / 首轮失败等用例不会走到组装，用 lenient 避免严格桩误报
        org.mockito.Mockito.lenient()
                .when(prompts.assembleLockedByPurpose(anyString(), any(), any()))
                .thenReturn("平台锁定抽取提示词");
        // 落库路径：同族无历史 → 新建
        when(items.findActiveByFactKeys(eq(AGENT_ID), eq(USER_ID), eq("LONG"), any()))
                .thenReturn(List.of());
        when(items.findActiveByHash(eq(AGENT_ID), eq(USER_ID), eq("LONG"), anyString()))
                .thenReturn(Optional.empty());
        when(items.insert(any(MemoryItem.class), eq(USER_ID))).thenReturn(501L);
    }

    /** 首次空闲抽一次；同一空闲重复扫被终态挡住；用户再发言后可再抽。 */
    @Test
    void sameIdleIsIdempotentButNextIdleAfterUserTurnExtractsAgain() {
        when(resolver.requireExtract(any(), eq(AGENT_ID))).thenReturn(99L);
        when(models.complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(EXTRACT_JSON);

        // 1) 新会话首次空闲
        extracts.onSessionEnd(policy(true), AGENT_ID, USER_ID, SESSION_ID, BODY);
        assertEquals(LongExtractMark.DONE.name(), gate.status(SESSION_ID));

        // 2) 同一次空闲被扫两次：已有终态 → already_processed，不再出站
        extracts.onSessionEnd(policy(true), AGENT_ID, USER_ID, SESSION_ID, BODY);
        verify(models, times(1)).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());

        // 3) 用户再次发言：删掉该 session 的标记行
        extracts.clearSessionMarker(SESSION_ID);
        assertFalse(gate.isProcessed(SESSION_ID));

        // 4) 再次空闲：表中无行，按新一轮再抽一次
        extracts.onSessionEnd(policy(true), AGENT_ID, USER_ID, SESSION_ID, BODY);
        verify(models, times(2)).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        assertEquals(LongExtractMark.DONE.name(), gate.status(SESSION_ID));
    }

    /** 总闸关时记 SKIPPED；用户再发言 + 开闸后再次空闲，允许补抽这一次（不是终身只抽一次）。 */
    @Test
    void skippedByKillSwitchCanExtractAfterUserTurnAndGateOn() {
        properties.setLongExtractBatchEnabled(false);
        extracts.onSessionEnd(policy(true), AGENT_ID, USER_ID, SESSION_ID, BODY);
        assertEquals("SKIPPED", gate.status(SESSION_ID));
        assertEquals("batch_kill_switch", gate.reason(SESSION_ID));
        verify(models, times(0)).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());

        // 开闸 + 用户又聊过
        properties.setLongExtractBatchEnabled(true);
        extracts.clearSessionMarker(SESSION_ID);
        when(resolver.requireExtract(any(), eq(AGENT_ID))).thenReturn(99L);
        when(models.complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(EXTRACT_JSON);

        extracts.onSessionEnd(policy(true), AGENT_ID, USER_ID, SESSION_ID, BODY);
        verify(models, times(1)).complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        assertEquals(LongExtractMark.DONE.name(), gate.status(SESSION_ID));
    }

    /** 第一次失败记 FAILED；用户再发言后再次空闲允许重试。 */
    @Test
    void failedCanRetryAfterUserTurn() {
        when(resolver.requireExtract(any(), eq(AGENT_ID)))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "没有可用的记忆抽取模型"));
        extracts.onSessionEnd(policy(true), AGENT_ID, USER_ID, SESSION_ID, BODY);
        assertTrue(gate.status(SESSION_ID).startsWith("FAILED"));

        extracts.clearSessionMarker(SESSION_ID);
        when(resolver.requireExtract(any(), eq(AGENT_ID))).thenReturn(99L);
        when(models.complete(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(EXTRACT_JSON);

        extracts.onSessionEnd(policy(true), AGENT_ID, USER_ID, SESSION_ID, BODY);
        assertEquals(LongExtractMark.DONE.name(), gate.status(SESSION_ID));
    }

    private static MemoryPolicy policy(boolean onSessionEnd) {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        policy.setEnableLongExtract(1);
        policy.setLongExtractOnSessionEnd(onSessionEnd ? 1 : 0);
        policy.setMaxLongItems(100);
        return policy;
    }

    /**
     * 内存版幂等闸门：一行一个 session，语义与 MySQL 实现一致（只认终态、clear 即删行）。
     */
    private static final class InMemoryGate implements LongExtractSessionGate {

        private final Map<Long, String[]> rows = new HashMap<>();

        @Override
        public boolean isProcessed(long sessionId) {
            String[] row = rows.get(sessionId);
            if (row == null) {
                return false;
            }
            return LongExtractMark.DONE.name().equals(row[0])
                    || LongExtractMark.FAILED.name().equals(row[0])
                    || LongExtractMark.SKIPPED.name().equals(row[0]);
        }

        @Override
        public void complete(long sessionId, LongExtractMark mark, String reason) {
            rows.put(sessionId, new String[] {mark.name(), reason});
        }

        @Override
        public void clear(long sessionId) {
            rows.remove(sessionId);
        }

        String status(long sessionId) {
            String[] row = rows.get(sessionId);
            return row == null ? null : row[0];
        }

        String reason(long sessionId) {
            String[] row = rows.get(sessionId);
            return row == null ? null : row[1];
        }
    }
}

package com.agenthub.billing.application;

import com.agenthub.billing.domain.model.BillingChannel;
import com.agenthub.billing.domain.model.MeterCommand;
import com.agenthub.billing.domain.model.TokenLedger;
import com.agenthub.billing.domain.repository.PriceRuleRepository;
import com.agenthub.billing.domain.repository.TokenLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计费流水业务唯一键（I2）。所属域：billing。
 * <p>
 * 覆盖三条硬约束：撞唯一键按幂等成功（读回已有行、不再计一次金额、不抛 5xx）；
 * 没有 dedupe_key 的流水保持原样；空键归一成 NULL（唯一索引允许多个 NULL）。
 * 不连库、不出站模型。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingMeterIdempotencyTest {

    private static final long SESSION_ID = 7L;
    private static final String DEDUPE_KEY = "v1:7:idem-key-0001:1";

    @Mock
    private TokenLedgerRepository ledgers;
    @Mock
    private PriceRuleRepository prices;

    private BillingMeterAppService service;

    @BeforeEach
    void setUp() {
        service = new BillingMeterAppService(ledgers, prices);
        when(prices.listCandidates(any(), any())).thenReturn(List.of());
        when(ledgers.insert(any(), any())).thenReturn(1L);
    }

    @Test
    void duplicateDedupeKeyIsIdempotentSuccess() {
        when(ledgers.insert(any(), any())).thenThrow(new DuplicateKeyException("uk_ledger_dedupe"));
        TokenLedger existing = new TokenLedger();
        existing.setId(88L);
        existing.setDedupeKey(DEDUPE_KEY);
        when(ledgers.findByDedupeKey(DEDUPE_KEY)).thenReturn(Optional.of(existing));

        // 已跑过一轮的同一计费事件：第二次写不得抛 5xx，也不再计一次金额
        service.record(command(DEDUPE_KEY));

        verify(ledgers).findByDedupeKey(DEDUPE_KEY);
        verify(ledgers).insert(any(), any());
    }

    @Test
    void duplicateWithoutDedupeKeyStillThrows() {
        when(ledgers.insert(any(), any())).thenThrow(new DuplicateKeyException("其它唯一约束"));

        // 没有业务键的流水不该吞掉与本刀无关的唯一冲突
        assertThrows(DuplicateKeyException.class, () -> service.record(command(null)));
        verify(ledgers, never()).findByDedupeKey(any());
    }

    @Test
    void conflictWithoutRowIsSwallowedNotBilled() {
        when(ledgers.insert(any(), any())).thenThrow(new DuplicateKeyException("uk_ledger_dedupe"));
        when(ledgers.findByDedupeKey(DEDUPE_KEY)).thenReturn(Optional.empty());

        // 冲突行读不回来：宁可跳过这一笔，也不能重复计费或打断主对话
        service.record(command(DEDUPE_KEY));

        verify(ledgers).findByDedupeKey(DEDUPE_KEY);
    }

    @Test
    void dedupeKeyIsCarriedToLedger() {
        service.record(command(DEDUPE_KEY));

        ArgumentCaptor<TokenLedger> captor = ArgumentCaptor.forClass(TokenLedger.class);
        verify(ledgers).insert(captor.capture(), any());
        assertEquals(DEDUPE_KEY, captor.getValue().getDedupeKey());
        assertEquals(SESSION_ID, captor.getValue().getSessionId());
        verify(ledgers, never()).findByDedupeKey(any());
    }

    @Test
    void blankDedupeKeyBecomesNull() {
        service.record(command("   "));

        ArgumentCaptor<TokenLedger> captor = ArgumentCaptor.forClass(TokenLedger.class);
        verify(ledgers).insert(captor.capture(), any());
        // 空串会被唯一索引当成同一个值互相撞，必须归一成 NULL
        assertNull(captor.getValue().getDedupeKey());
    }

    @Test
    void oversizedDedupeKeyIsTruncatedToColumnWidth() {
        String longKey = "v1:" + "x".repeat(200);
        service.record(command(longKey));

        ArgumentCaptor<TokenLedger> captor = ArgumentCaptor.forClass(TokenLedger.class);
        verify(ledgers).insert(captor.capture(), any());
        assertEquals(128, captor.getValue().getDedupeKey().length());
    }

    /**
     * @param dedupeKey 业务唯一键，可空
     * @return 一条最小可用的计量命令
     */
    private MeterCommand command(String dedupeKey) {
        MeterCommand command = new MeterCommand();
        command.setAgentId(9L);
        command.setModelInstanceId(3L);
        command.setSessionId(SESSION_ID);
        command.setChannel(BillingChannel.CONSOLE);
        command.setTokenPrompt(10);
        command.setTokenCompletion(5);
        command.setOperatorId(100L);
        command.setDedupeKey(dedupeKey);
        return command;
    }
}

package com.agenthub.billing.domain.service;

import com.agenthub.billing.domain.model.DailyTotals;
import com.agenthub.billing.domain.model.TokenLedger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 日汇总按 agent、模型、应用和渠道加总。作废行不进合计。
 */
class DailyRollupTest {

    private final LocalDate day = LocalDate.of(2026, 10, 26);

    @Test
    void sumsSameDimensionAndSkipsVoided() {
        TokenLedger first = ledger(1, 8L, 9L, "CONSOLE", 2, 3, "0.0800", "CNY");
        TokenLedger second = ledger(1, 8L, 9L, "CONSOLE", 4, 5, "0.1400", "CNY");
        TokenLedger voided = ledger(0, 8L, 9L, "CONSOLE", 100, 100, "9", "CNY");
        TokenLedger other = ledger(1, 8L, 9L, "APP_API", 1, 1, null, null);
        List<DailyTotals> totals = DailyRollup.aggregate(List.of(first, second, voided, other));
        assertEquals(2, totals.size());
        DailyTotals console = totals.get(0);
        assertEquals(6L, console.getTokenPrompt());
        assertEquals(8L, console.getTokenCompletion());
        assertEquals(14L, console.getTokenTotal());
        assertEquals(new BigDecimal("0.2200"), console.getAmount());
        assertEquals("CNY", console.getCurrency());
        assertEquals("APP_API", totals.get(1).getChannel());
        assertNull(totals.get(1).getAmount());
    }

    @Test
    void mixedCurrencyDropsAmount() {
        TokenLedger cny = ledger(1, 1L, 1L, "CONSOLE", 1, 1, "1.0000", "CNY");
        TokenLedger usd = ledger(1, 1L, 1L, "CONSOLE", 2, 2, "2.0000", "USD");
        DailyTotals totals = DailyRollup.aggregate(List.of(cny, usd)).get(0);
        assertEquals(3L, totals.getTokenPrompt());
        assertNull(totals.getAmount());
        assertNull(totals.getCurrency());
    }

    private TokenLedger ledger(int status, Long agentId, Long modelId, String channel,
                               int prompt, int completion, String amount, String currency) {
        TokenLedger ledger = new TokenLedger();
        ledger.setBizDate(day);
        ledger.setStatus(status);
        ledger.setAgentId(agentId);
        ledger.setModelInstanceId(modelId);
        ledger.setChannel(channel);
        ledger.setTokenPrompt(prompt);
        ledger.setTokenCompletion(completion);
        if (amount != null) {
            ledger.setAmount(new BigDecimal(amount));
        }
        ledger.setCurrency(currency);
        return ledger;
    }
}

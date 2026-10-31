package com.agenthub.billing.domain.service;

import com.agenthub.billing.domain.model.DailyTotals;
import com.agenthub.billing.domain.model.TokenLedger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把正常流水按日和计量维加总。所属域：billing。
 * 作废流水不参与。同一维里出现多种币种时金额留空，token 仍然相加。
 */
public final class DailyRollup {

    private DailyRollup() {
    }

    /**
     * @param ledgers 已限定日期范围的流水；status 非 1 的行会被跳过
     * @return 每个维度一条，顺序与首次出现一致
     */
    public static List<DailyTotals> aggregate(List<TokenLedger> ledgers) {
        Map<Key, Accumulator> groups = new LinkedHashMap<>();
        if (ledgers == null) {
            return List.of();
        }
        for (TokenLedger ledger : ledgers) {
            if (ledger == null || ledger.getBizDate() == null || ledger.getStatus() == null || ledger.getStatus() != 1) {
                continue;
            }
            Key key = new Key(ledger.getBizDate(), ledger.getAgentId(), ledger.getModelInstanceId(),
                    ledger.getAppId(), ledger.getChannel());
            groups.computeIfAbsent(key, Key::start).add(ledger);
        }
        List<DailyTotals> totals = new ArrayList<>();
        for (Accumulator accumulator : groups.values()) {
            totals.add(accumulator.toTotals());
        }
        return totals;
    }

    private record Key(java.time.LocalDate bizDate, Long agentId, Long modelInstanceId, Long appId, String channel) {

        private Accumulator start() {
            return new Accumulator(this);
        }
    }

    private static final class Accumulator {

        private final Key key;
        private long prompt;
        private long completion;
        private BigDecimal amount;
        private String currency;
        private boolean mixedCurrency;

        private Accumulator(Key key) {
            this.key = key;
        }

        private void add(TokenLedger ledger) {
            prompt += nonNegative(ledger.getTokenPrompt());
            completion += nonNegative(ledger.getTokenCompletion());
            if (ledger.getAmount() == null || mixedCurrency) {
                return;
            }
            if (amount == null) {
                amount = ledger.getAmount();
                currency = ledger.getCurrency();
                return;
            }
            if (!Objects.equals(currency, ledger.getCurrency())) {
                mixedCurrency = true;
                amount = null;
                currency = null;
                return;
            }
            amount = amount.add(ledger.getAmount());
        }

        private DailyTotals toTotals() {
            DailyTotals totals = new DailyTotals();
            totals.setBizDate(key.bizDate());
            totals.setAgentId(key.agentId());
            totals.setModelInstanceId(key.modelInstanceId());
            totals.setAppId(key.appId());
            totals.setChannel(key.channel());
            totals.setTokenPrompt(prompt);
            totals.setTokenCompletion(completion);
            totals.setTokenTotal(prompt + completion);
            if (amount != null) {
                totals.setAmount(amount.setScale(4, RoundingMode.HALF_UP));
            }
            totals.setCurrency(currency);
            return totals;
        }

        private static long nonNegative(Integer value) {
            if (value == null || value < 0) {
                return 0L;
            }
            return value;
        }
    }
}

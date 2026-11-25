package com.agenthub.billing.domain.service;

import com.agenthub.billing.domain.model.PriceRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 单价匹配：实例规则优先，按每个 token 计价。
 */
class PriceMatcherTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 26, 12, 0);

    @Test
    void specificRuleBeatsDefault() {
        PriceRule fallback = rule(1L, null, "0.100000", "0.200000", now.minusDays(2));
        PriceRule specific = rule(2L, 9L, "0.010000", "0.020000", now.minusDays(1));
        PriceRule picked = PriceMatcher.pick(List.of(fallback, specific), 9L, now);
        assertEquals(2L, picked.getId());
        PriceMatcher.PricedUsage priced = PriceMatcher.price(picked, 2, 3);
        assertEquals(new BigDecimal("0.0800"), priced.amount());
        assertEquals(new BigDecimal("0.010000"), priced.unitPricePrompt());
    }

    @Test
    void laterWindowWinsWhenBothMatch() {
        PriceRule older = rule(1L, 9L, "0.01", "0.02", now.minusDays(3));
        PriceRule newer = rule(2L, 9L, "0.03", "0.04", now.minusDays(1));
        assertEquals(2L, PriceMatcher.pick(List.of(older, newer), 9L, now).getId());
    }

    @Test
    void missingRuleLeavesAmountEmpty() {
        PriceMatcher.PricedUsage priced = PriceMatcher.price(null, 4, 5);
        assertNull(priced.amount());
        assertNull(priced.currency());
    }

    @Test
    void disabledOrFutureRuleIsSkipped() {
        PriceRule disabled = rule(1L, 9L, "0.01", "0.02", now.minusDays(1));
        disabled.setStatus(0);
        PriceRule future = rule(2L, null, "0.01", "0.02", now.plusDays(1));
        assertNull(PriceMatcher.pick(List.of(disabled, future), 9L, now));
    }

    private PriceRule rule(Long id, Long modelId, String prompt, String completion, LocalDateTime from) {
        PriceRule rule = new PriceRule();
        rule.setId(id);
        rule.setModelInstanceId(modelId);
        rule.setPricePrompt(new BigDecimal(prompt));
        rule.setPriceCompletion(new BigDecimal(completion));
        rule.setCurrency("CNY");
        rule.setEffectiveFrom(from);
        rule.setStatus(1);
        return rule;
    }
}

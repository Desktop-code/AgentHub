package com.agenthub.billing.domain.service;

import com.agenthub.billing.domain.model.PriceRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 挑选生效单价并按每个 token 计价。所属域：billing。
 * 模型实例规则优先于 modelInstanceId 为空的默认规则。没有规则就只计量。
 */
public final class PriceMatcher {

    private static final int AMOUNT_SCALE = 4;
    /** DECIMAL(14,4) 小数点前最多 10 位，超出则放弃金额，token 仍然保留 */
    private static final int AMOUNT_INTEGER_DIGITS = 10;

    private PriceMatcher() {
    }

    /**
     * @param rules           候选规则，可含停用或未生效
     * @param modelInstanceId 本步模型；空则只考虑默认规则
     * @param at              调用发生时间
     * @return 命中的规则；没有则为 null
     */
    public static PriceRule pick(List<PriceRule> rules, Long modelInstanceId, LocalDateTime at) {
        if (rules == null || rules.isEmpty() || at == null) {
            return null;
        }
        PriceRule specific = null;
        PriceRule fallback = null;
        for (PriceRule rule : rules) {
            if (!active(rule, at)) {
                continue;
            }
            if (modelInstanceId != null && modelInstanceId.equals(rule.getModelInstanceId())) {
                specific = later(specific, rule);
            } else if (rule.getModelInstanceId() == null) {
                fallback = later(fallback, rule);
            }
        }
        return specific != null ? specific : fallback;
    }

    /**
     * 金额 = prompt 数 × prompt 单价 + completion 数 × completion 单价。
     *
     * @param rule             命中的规则，可空
     * @param promptTokens     已归零的 prompt 数
     * @param completionTokens 已归零的 completion 数
     * @return 快照；无规则时金额和单价都是 null
     */
    public static PricedUsage price(PriceRule rule, int promptTokens, int completionTokens) {
        if (rule == null || rule.getPricePrompt() == null || rule.getPriceCompletion() == null) {
            return PricedUsage.unpriced();
        }
        BigDecimal amount = rule.getPricePrompt().multiply(BigDecimal.valueOf(promptTokens))
                .add(rule.getPriceCompletion().multiply(BigDecimal.valueOf(completionTokens)))
                .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        if (integerDigits(amount) > AMOUNT_INTEGER_DIGITS) {
            amount = null;
        }
        return new PricedUsage(rule.getPricePrompt(), rule.getPriceCompletion(), amount, rule.getCurrency());
    }

    private static boolean active(PriceRule rule, LocalDateTime at) {
        if (rule == null || rule.getStatus() == null || rule.getStatus() != 1 || rule.getEffectiveFrom() == null) {
            return false;
        }
        if (rule.getEffectiveFrom().isAfter(at)) {
            return false;
        }
        return rule.getEffectiveTo() == null || !rule.getEffectiveTo().isBefore(at);
    }

    /** 生效起点更晚的优先；起点相同取 id 更大的，避免两条同时生效时不确定。 */
    private static PriceRule later(PriceRule current, PriceRule candidate) {
        if (current == null) {
            return candidate;
        }
        int byFrom = Comparator.nullsFirst(LocalDateTime::compareTo)
                .compare(current.getEffectiveFrom(), candidate.getEffectiveFrom());
        if (byFrom != 0) {
            return byFrom > 0 ? current : candidate;
        }
        long currentId = current.getId() == null ? 0L : current.getId();
        long candidateId = candidate.getId() == null ? 0L : candidate.getId();
        return currentId >= candidateId ? current : candidate;
    }

    private static int integerDigits(BigDecimal amount) {
        BigDecimal normalized = amount.stripTrailingZeros();
        int scale = Math.max(normalized.scale(), 0);
        return Math.max(normalized.precision() - scale, 1);
    }

    /**
     * 写入流水的单价快照。所属域：billing。
     *
     * @param unitPricePrompt     prompt 单价，可空
     * @param unitPriceCompletion completion 单价，可空
     * @param amount              金额，可空
     * @param currency            币种，可空
     */
    public record PricedUsage(BigDecimal unitPricePrompt, BigDecimal unitPriceCompletion,
                              BigDecimal amount, String currency) {

        /**
         * @return 只计量、不计价
         */
        public static PricedUsage unpriced() {
            return new PricedUsage(null, null, null, null);
        }
    }
}

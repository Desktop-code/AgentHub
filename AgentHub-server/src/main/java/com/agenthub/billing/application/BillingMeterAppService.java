package com.agenthub.billing.application;

import com.agenthub.billing.domain.BillingMeter;
import com.agenthub.billing.domain.model.BillingChannel;
import com.agenthub.billing.domain.model.MeterCommand;
import com.agenthub.billing.domain.model.PriceRule;
import com.agenthub.billing.domain.model.TokenLedger;
import com.agenthub.billing.domain.repository.PriceRuleRepository;
import com.agenthub.billing.domain.repository.TokenLedgerRepository;
import com.agenthub.billing.domain.service.PriceMatcher;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 写 Token 流水。所属域：billing。
 * 每调一次记一条，status 恒为正常。没有单价规则时金额留空。
 * 不调用模型，也不在会话结束时补汇总。
 * <p>
 * 幂等：带 {@code dedupeKey} 的流水撞唯一键 {@code uk_ledger_dedupe} 时视为**幂等成功**——
 * 读回已有行、不再计一次金额，也不把冲突冒成 5xx 打断主对话。冲突只记 debug/info。
 */
@Service
public class BillingMeterAppService implements BillingMeter {

    private static final Logger log = LoggerFactory.getLogger(BillingMeterAppService.class);

    /** 业务日按控制台时区切，和 Jackson 时区一致 */
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TokenLedgerRepository ledgers;
    private final PriceRuleRepository prices;

    /**
     * @param ledgers 流水
     * @param prices  单价候选
     */
    public BillingMeterAppService(TokenLedgerRepository ledgers, PriceRuleRepository prices) {
        this.ledgers = ledgers;
        this.prices = prices;
    }

    /**
     * {@inheritDoc}
     * 渠道必须是约定值。token 为空或负数按 0。流水状态不接受入参，固定写正常。
     */
    @Override
    @Transactional
    public void record(MeterCommand command) {
        if (command == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "计量内容不能为空");
        }
        String channel = command.getChannel() == null ? "" : command.getChannel().trim();
        if (!BillingChannel.known(channel)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "计量渠道不正确");
        }
        int prompt = nonNegative(command.getTokenPrompt());
        int completion = nonNegative(command.getTokenCompletion());
        LocalDateTime now = LocalDateTime.now(ZONE);
        PriceRule rule = PriceMatcher.pick(prices.listCandidates(command.getModelInstanceId(), now),
                command.getModelInstanceId(), now);
        PriceMatcher.PricedUsage priced = PriceMatcher.price(rule, prompt, completion);
        TokenLedger ledger = new TokenLedger();
        ledger.setBizDate(now.toLocalDate());
        ledger.setAgentId(command.getAgentId());
        ledger.setModelInstanceId(command.getModelInstanceId());
        ledger.setAppId(command.getAppId());
        ledger.setSessionId(command.getSessionId());
        ledger.setChannel(channel);
        ledger.setTokenPrompt(prompt);
        ledger.setTokenCompletion(completion);
        ledger.setTokenTotal(total(prompt, completion));
        ledger.setUnitPricePrompt(priced.unitPricePrompt());
        ledger.setUnitPriceCompletion(priced.unitPriceCompletion());
        ledger.setAmount(priced.amount());
        ledger.setCurrency(priced.currency());
        ledger.setStatus(BillingChecks.ENABLED);
        ledger.setRemark(BillingChecks.remark(command.getRemark()));
        ledger.setDedupeKey(normaliseKey(command.getDedupeKey()));
        insertIdempotently(ledger, command.getOperatorId());
    }

    /**
     * 落一条流水；带业务唯一键且撞唯一约束时按幂等成功处理。
     * <p>
     * 为什么在同一个事务里 catch：一次重复的计量只应「什么都没发生」，读回已有行即可；
     * 让 {@link DuplicateKeyException} 冒出去会被上层包成 500，直接打断正在收尾的对话。
     * MySQL 下单条 INSERT 失败不会使整个事务回滚，catch 后照常提交（空操作）是安全的。
     *
     * @param ledger     待落流水
     * @param operatorId 创建人
     */
    private void insertIdempotently(TokenLedger ledger, Long operatorId) {
        try {
            ledgers.insert(ledger, operatorId);
        } catch (DuplicateKeyException ex) {
            if (ledger.getDedupeKey() == null) {
                // 没有 dedupe_key 却撞唯一键：与本刀无关的约束被违反，照实抛给上层
                throw ex;
            }
            TokenLedger existing = ledgers.findByDedupeKey(ledger.getDedupeKey()).orElse(null);
            if (existing == null) {
                // 冲突行读不回来（并发删除/作废的极窄窗口）：不重复计费，只留痕
                log.info("计量流水唯一冲突但读不回，按幂等成功跳过 dedupeKey={} type={}",
                        ledger.getDedupeKey(), ex.getClass().getSimpleName());
                return;
            }
            log.debug("计量流水幂等命中，跳过重复计费 dedupeKey={} ledgerId={}",
                    ledger.getDedupeKey(), existing.getId());
        }
    }

    /**
     * 空串统一成 null：MySQL 唯一索引允许多个 NULL，而空串会被当成同一个值互相撞。
     *
     * @param raw 原始键
     * @return 可用键或 null
     */
    private String normaliseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() > 128 ? trimmed.substring(0, 128) : trimmed;
    }

    private int nonNegative(Integer value) {
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }

    private int total(int prompt, int completion) {
        long sum = (long) prompt + completion;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}

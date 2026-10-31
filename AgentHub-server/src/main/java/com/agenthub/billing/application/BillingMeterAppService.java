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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 写 Token 流水。所属域：billing。
 * 每调一次记一条，status 恒为正常。没有单价规则时金额留空。
 * 不调用模型，也不在会话结束时补汇总。
 */
@Service
public class BillingMeterAppService implements BillingMeter {

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
        ledgers.insert(ledger, command.getOperatorId());
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

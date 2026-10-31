package com.agenthub.billing.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.billing.api.dto.PriceResp;
import com.agenthub.billing.api.dto.PriceSaveReq;
import com.agenthub.billing.api.dto.PriceStatusReq;
import com.agenthub.billing.domain.model.PriceRule;
import com.agenthub.billing.domain.repository.PriceRuleRepository;
import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单价规则。所属域：billing。
 * 写操作记操作日志，日志里只有名称和动作，不含模型 Key。
 */
@Service
public class BillingPriceAppService {

    private final PriceRuleRepository prices;
    private final ModelInstanceRepository instances;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param prices          单价
     * @param instances       校验模型实例是否还在
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     */
    public BillingPriceAppService(PriceRuleRepository prices, ModelInstanceRepository instances,
                                  OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.prices = prices;
        this.instances = instances;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @param req 分页
     * @return 单价页
     */
    public PageResult<PriceResp> page(PageQuery req) {
        PageResult<PriceRule> page = prices.page(req.getCurrent(), req.getSize());
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toResp).toList());
    }

    /**
     * 新建后默认启用。启停走单独接口。
     *
     * @param req 单价
     * @return 已保存的规则
     */
    @Transactional
    public PriceResp create(PriceSaveReq req) {
        PriceRule rule = fill(new PriceRule(), req);
        rule.setStatus(BillingChecks.ENABLED);
        Long id = prices.insert(rule, operatorContext.requireUserId());
        operLogWriter.write("billing", "create", "billing_price_rule", String.valueOf(id), "创建单价 " + rule.getName());
        return get(id);
    }

    /**
     * @param id  主键
     * @param req 单价，不改启停
     * @return 更新后的规则
     */
    @Transactional
    public PriceResp update(Long id, PriceSaveReq req) {
        PriceRule existing = require(id);
        fill(existing, req);
        prices.updateProfile(existing, operatorContext.requireUserId());
        operLogWriter.write("billing", "update", "billing_price_rule", String.valueOf(id), "更新单价 " + existing.getName());
        return get(id);
    }

    /**
     * @param id  主键
     * @param req 目标状态
     * @return 更新后的规则
     */
    @Transactional
    public PriceResp changeStatus(Long id, PriceStatusReq req) {
        PriceRule existing = require(id);
        int status = BillingChecks.status(req.getStatus());
        prices.updateStatus(id, status, operatorContext.requireUserId());
        String action = status == BillingChecks.ENABLED ? "enable" : "disable";
        operLogWriter.write("billing", action, "billing_price_rule", String.valueOf(id), "更新单价状态 " + existing.getName());
        return get(id);
    }

    /**
     * 逻辑删除。
     *
     * @param id 主键
     */
    @Transactional
    public void remove(Long id) {
        PriceRule existing = require(id);
        prices.logicalDelete(id, operatorContext.requireUserId());
        operLogWriter.write("billing", "delete", "billing_price_rule", String.valueOf(id), "删除单价 " + existing.getName());
    }

    private PriceRule fill(PriceRule rule, PriceSaveReq req) {
        rule.setName(BillingChecks.requiredText(req.getName(), 64, "名称"));
        rule.setPricePrompt(BillingChecks.price(req.getPricePrompt(), "prompt 单价"));
        rule.setPriceCompletion(BillingChecks.price(req.getPriceCompletion(), "completion 单价"));
        rule.setCurrency(BillingChecks.currency(req.getCurrency()));
        BillingChecks.window(req.getEffectiveFrom(), req.getEffectiveTo());
        rule.setEffectiveFrom(req.getEffectiveFrom());
        rule.setEffectiveTo(req.getEffectiveTo());
        rule.setRemark(BillingChecks.remark(req.getRemark()));
        if (req.getModelInstanceId() != null && instances.findById(req.getModelInstanceId()).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "模型实例不存在");
        }
        rule.setModelInstanceId(req.getModelInstanceId());
        return rule;
    }

    private PriceResp get(Long id) {
        return toResp(require(id));
    }

    private PriceRule require(Long id) {
        return prices.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "单价不存在"));
    }

    private PriceResp toResp(PriceRule rule) {
        PriceResp resp = new PriceResp();
        resp.setId(rule.getId());
        resp.setModelInstanceId(rule.getModelInstanceId());
        resp.setName(rule.getName());
        resp.setPricePrompt(rule.getPricePrompt());
        resp.setPriceCompletion(rule.getPriceCompletion());
        resp.setCurrency(rule.getCurrency());
        resp.setEffectiveFrom(rule.getEffectiveFrom());
        resp.setEffectiveTo(rule.getEffectiveTo());
        resp.setStatus(rule.getStatus());
        resp.setRemark(rule.getRemark());
        return resp;
    }
}

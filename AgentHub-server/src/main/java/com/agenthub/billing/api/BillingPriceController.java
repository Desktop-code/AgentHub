package com.agenthub.billing.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.billing.api.dto.PriceResp;
import com.agenthub.billing.api.dto.PriceSaveReq;
import com.agenthub.billing.api.dto.PriceStatusReq;
import com.agenthub.billing.application.BillingPriceAppService;
import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单价规则。所属域：billing。校验和审计在应用服务。
 */
@RestController
@RequestMapping("/api/billing/prices")
public class BillingPriceController {

    private final BillingPriceAppService prices;

    /**
     * @param prices 单价用例
     */
    public BillingPriceController(BillingPriceAppService prices) {
        this.prices = prices;
    }

    /**
     * @param req 分页
     * @return 单价页
     */
    @SaCheckPermission(BillingPermissions.PRICE_LIST)
    @GetMapping
    public Result<PageResult<PriceResp>> page(@Valid PageQuery req) {
        return Result.ok(prices.page(req));
    }

    /**
     * @param req 新规则，默认启用
     * @return 已保存的规则
     */
    @SaCheckPermission(BillingPermissions.PRICE_ADD)
    @PostMapping
    public Result<PriceResp> create(@Valid @RequestBody PriceSaveReq req) {
        return Result.ok(prices.create(req));
    }

    /**
     * @param id  主键
     * @param req 名称、单价和生效期
     * @return 更新后的规则
     */
    @SaCheckPermission(BillingPermissions.PRICE_EDIT)
    @PutMapping("/{id}")
    public Result<PriceResp> update(@PathVariable Long id, @Valid @RequestBody PriceSaveReq req) {
        return Result.ok(prices.update(id, req));
    }

    /**
     * @param id 主键
     * @return 空数据
     */
    @SaCheckPermission(BillingPermissions.PRICE_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        prices.remove(id);
        return Result.ok();
    }

    /**
     * @param id  主键
     * @param req 目标状态
     * @return 更新后的规则
     */
    @SaCheckPermission(BillingPermissions.PRICE_ENABLE)
    @PutMapping("/{id}/status")
    public Result<PriceResp> changeStatus(@PathVariable Long id, @Valid @RequestBody PriceStatusReq req) {
        return Result.ok(prices.changeStatus(id, req));
    }
}

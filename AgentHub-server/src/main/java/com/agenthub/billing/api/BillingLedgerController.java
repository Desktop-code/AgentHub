package com.agenthub.billing.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.billing.api.dto.LedgerPageReq;
import com.agenthub.billing.api.dto.LedgerResp;
import com.agenthub.billing.application.BillingQueryAppService;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token 流水查询。所属域：billing。只读，没有作废接口。
 */
@RestController
@RequestMapping("/api/billing/ledgers")
public class BillingLedgerController {

    private final BillingQueryAppService queries;

    /**
     * @param queries 流水查询
     */
    public BillingLedgerController(BillingQueryAppService queries) {
        this.queries = queries;
    }

    /**
     * @param req 分页；日期、Agent、模型、渠道可空
     * @return 流水页
     */
    @SaCheckPermission(BillingPermissions.LEDGER_LIST)
    @GetMapping
    public Result<PageResult<LedgerResp>> page(@Valid LedgerPageReq req) {
        return Result.ok(queries.ledgers(req));
    }
}

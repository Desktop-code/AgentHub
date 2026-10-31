package com.agenthub.billing.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.billing.api.dto.DailyPageReq;
import com.agenthub.billing.api.dto.DailyRebuildReq;
import com.agenthub.billing.api.dto.DailyResp;
import com.agenthub.billing.api.dto.DailyTrendPointResp;
import com.agenthub.billing.api.dto.DailyTrendReq;
import com.agenthub.billing.application.BillingDailyAppService;
import com.agenthub.billing.application.BillingQueryAppService;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 日汇总查询和重算。所属域：billing。重算与查询共用 billing:daily:list。
 */
@RestController
@RequestMapping("/api/billing/daily")
public class BillingDailyController {

    private final BillingQueryAppService queries;
    private final BillingDailyAppService daily;

    /**
     * @param queries 查询
     * @param daily   重算
     */
    public BillingDailyController(BillingQueryAppService queries, BillingDailyAppService daily) {
        this.queries = queries;
        this.daily = daily;
    }

    /**
     * @param req 分页和可选维度
     * @return 日汇总页
     */
    @SaCheckPermission(BillingPermissions.DAILY_LIST)
    @GetMapping
    public Result<PageResult<DailyResp>> page(@Valid DailyPageReq req) {
        return Result.ok(queries.daily(req));
    }

    /**
     * 趋势图接口：按天聚合 token 消耗，供柱状图 + 折线图渲染。
     *
     * @param req 日期区间与可选维度；日期缺省时取最近 7 天
     * @return 按日期升序的趋势点，缺数据的日子补 0
     */
    @SaCheckPermission(BillingPermissions.DAILY_LIST)
    @GetMapping("/trend")
    public Result<List<DailyTrendPointResp>> trend(@Valid DailyTrendReq req) {
        return Result.ok(queries.trend(req));
    }

    /**
     * 按业务日覆盖同维汇总。
     *
     * @param req 闭区间
     * @return 有用量的维度数
     */
    @SaCheckPermission(BillingPermissions.DAILY_LIST)
    @PostMapping("/rebuild")
    public Result<Integer> rebuild(@Valid @RequestBody DailyRebuildReq req) {
        return Result.ok(daily.rebuild(req));
    }
}

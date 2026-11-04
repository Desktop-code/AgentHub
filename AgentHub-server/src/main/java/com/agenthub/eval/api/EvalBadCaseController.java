package com.agenthub.eval.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.eval.api.dto.EvalBadCasePageReq;
import com.agenthub.eval.api.dto.EvalBadCaseResp;
import com.agenthub.eval.api.dto.EvalImportResp;
import com.agenthub.eval.api.dto.EvalPromoteFromResultReq;
import com.agenthub.eval.api.dto.EvalPromoteFromTraceReq;
import com.agenthub.eval.application.EvalBadCaseAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 坏案列表与回流接口。所属域：eval。
 * <p>列表：{@code GET /api/eval/results?verdict=FAIL}（实现为独立路径，避免与 runs/{id}/results 冲突）。
 * 回流：{@code POST /api/eval/suites/{id}/cases/from-result|from-trace}。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalBadCaseController {

    private final EvalBadCaseAppService badCases;

    /**
     * @param badCases 坏案用例
     */
    public EvalBadCaseController(EvalBadCaseAppService badCases) {
        this.badCases = badCases;
    }

    /**
     * 跨 run 失败结果分页（坏案 Tab）。
     *
     * @param req 查询（默认仅 FAIL）
     * @return 分页
     */
    @SaCheckPermission(EvalPermissions.RUN_VIEW)
    @GetMapping("/results")
    public Result<PageResult<EvalBadCaseResp>> pageResults(@Valid EvalBadCasePageReq req) {
        return Result.ok(badCases.pageBadCases(req));
    }

    /**
     * 从评测结果提升为新 case（升版）。
     *
     * @param id  目标套件
     * @param req 源 result
     * @return 升版结果
     */
    @SaCheckPermission(EvalPermissions.SUITE_EDIT)
    @PostMapping("/suites/{id:\\d+}/cases/from-result")
    public Result<EvalImportResp> fromResult(@PathVariable Long id,
                                             @Valid @RequestBody EvalPromoteFromResultReq req) {
        return Result.ok(badCases.promoteFromResult(id, req));
    }

    /**
     * 从观测摘要提升为新 case（不读 obs 表）。
     *
     * @param id  目标套件
     * @param req 摘要
     * @return 升版结果
     */
    @SaCheckPermission(EvalPermissions.SUITE_EDIT)
    @PostMapping("/suites/{id:\\d+}/cases/from-trace")
    public Result<EvalImportResp> fromTrace(@PathVariable Long id,
                                            @Valid @RequestBody EvalPromoteFromTraceReq req) {
        return Result.ok(badCases.promoteFromTrace(id, req));
    }
}

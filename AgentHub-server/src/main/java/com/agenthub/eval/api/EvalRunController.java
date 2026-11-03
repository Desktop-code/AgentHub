package com.agenthub.eval.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.eval.api.dto.EvalResultPageReq;
import com.agenthub.eval.api.dto.EvalResultResp;
import com.agenthub.eval.api.dto.EvalRunCreateReq;
import com.agenthub.eval.api.dto.EvalRunPageReq;
import com.agenthub.eval.api.dto.EvalRunResp;
import com.agenthub.eval.application.EvalRunAppService;
import com.agenthub.eval.application.EvalRunnerAppService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评测跑批接口。所属域：eval。
 * <p>路径对齐定稿 §5.4 runs 子集 + execute + report：
 * <pre>
 *   GET  /api/eval/runs                跑批分页
 *   POST /api/eval/runs                建跑（PENDING）
 *   POST /api/eval/runs/{id}/execute   开跑
 *   GET  /api/eval/runs/{id}           查状态与汇总
 *   GET  /api/eval/runs/{id}/results   分页结果
 *   GET  /api/eval/runs/{id}/report    报告（JSON 默认；?format=md）
 *   POST /api/eval/runs/{id}/cancel    取消
 * </pre>
 * <p>坏案列表挂在 {@code /api/eval/results}（同 Controller 类外另映射见 {@link EvalBadCaseController}）。
 * <p>控制器零业务；无 SSE。
 */
@RestController
@RequestMapping("/api/eval/runs")
public class EvalRunController {

    private final EvalRunAppService runs;
    private final EvalRunnerAppService runner;

    /**
     * @param runs   跑批壳（建/查/取消/报告）
     * @param runner 真执行
     */
    public EvalRunController(EvalRunAppService runs, EvalRunnerAppService runner) {
        this.runs = runs;
        this.runner = runner;
    }

    /**
     * 本租户跑批分页；新在前。
     *
     * @param req 筛选
     * @return 分页
     */
    @SaCheckPermission(EvalPermissions.RUN_VIEW)
    @GetMapping
    public Result<PageResult<EvalRunResp>> page(@Valid EvalRunPageReq req) {
        return Result.ok(runs.pageRuns(req));
    }

    /**
     * 建跑：落 PENDING 的 run 与 N 条占位结果行。
     *
     * @param req 建跑请求
     * @return 新跑批（status=PENDING）
     */
    @SaCheckPermission(EvalPermissions.RUN_TRIGGER)
    @PostMapping
    public Result<EvalRunResp> create(@Valid @RequestBody EvalRunCreateReq req) {
        return Result.ok(runs.createRun(req));
    }

    /**
     * 显式开跑：同步串行执行尚未终局的 case，返回收束后的 run。
     *
     * @param id 跑批主键
     * @return 更新后的跑批
     */
    @SaCheckPermission(EvalPermissions.RUN_TRIGGER)
    @PostMapping("/{id:\\d+}/execute")
    public Result<EvalRunResp> execute(@PathVariable Long id) {
        return Result.ok(runner.execute(id));
    }

    /**
     * 查单条跑批的状态与汇总计数。
     *
     * @param id 主键
     * @return 跑批出参
     */
    @SaCheckPermission(EvalPermissions.RUN_VIEW)
    @GetMapping("/{id:\\d+}")
    public Result<EvalRunResp> get(@PathVariable Long id) {
        return Result.ok(runs.getRun(id));
    }

    /**
     * 分页列出该跑批的逐 case 结果（按 sort_order）。
     *
     * @param id  跑批主键
     * @param req 分页参数
     * @return 结果页
     */
    @SaCheckPermission(EvalPermissions.RUN_VIEW)
    @GetMapping("/{id:\\d+}/results")
    public Result<PageResult<EvalResultResp>> results(@PathVariable Long id, @Valid EvalResultPageReq req) {
        return Result.ok(runs.listResults(id, req));
    }

    /**
     * 读取报告。默认 JSON（包在 Result.data）；{@code format=md} 直接返回 text/markdown。
     * <p>未生成 → 业务 404「报告尚未生成」。
     *
     * @param id     跑批
     * @param format json（默认）或 md
     * @return JSON Result 或 Markdown 原文
     */
    @SaCheckPermission(EvalPermissions.RUN_VIEW)
    @GetMapping("/{id:\\d+}/report")
    public Object report(@PathVariable Long id,
                         @RequestParam(value = "format", required = false) String format) {
        Object payload = runs.getReport(id, format);
        if (format != null && "md".equalsIgnoreCase(format.trim())) {
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
                    .body(payload == null ? "" : String.valueOf(payload));
        }
        return Result.ok(payload);
    }

    /**
     * 取消跑批：级联 cancel in-flight trial，未出判定的 case 置 SKIPPED。
     *
     * @param id 跑批主键
     * @return 收尾后的跑批
     */
    @SaCheckPermission(EvalPermissions.RUN_CANCEL)
    @PostMapping("/{id:\\d+}/cancel")
    public Result<EvalRunResp> cancel(@PathVariable Long id) {
        return Result.ok(runs.cancel(id));
    }
}

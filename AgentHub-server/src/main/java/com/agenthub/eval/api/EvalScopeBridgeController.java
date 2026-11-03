package com.agenthub.eval.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.eval.api.dto.EvalScopeExportReq;
import com.agenthub.eval.api.dto.EvalScopeExportResp;
import com.agenthub.eval.application.EvalScopeBridgeAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EvalScope 可选桥接口。所属域：eval。
 * <p>{@code POST /api/eval/runs/{id}/evalscope/export}：落盘导出；body.trigger=true 可选外触发。
 * 权限复用 {@link EvalPermissions#RUN_TRIGGER}。未启用 → 业务错；不挂 Runner。
 */
@RestController
@RequestMapping("/api/eval/runs")
public class EvalScopeBridgeController {

    private final EvalScopeBridgeAppService bridge;

    /**
     * @param bridge 桥用例
     */
    public EvalScopeBridgeController(EvalScopeBridgeAppService bridge) {
        this.bridge = bridge;
    }

    /**
     * 导出（+可选触发）EvalScope 清单。
     *
     * @param id  跑批
     * @param req 可选参数；可空 body
     * @return 状态与路径
     */
    @SaCheckPermission(EvalPermissions.RUN_TRIGGER)
    @PostMapping("/{id:\\d+}/evalscope/export")
    public Result<EvalScopeExportResp> export(@PathVariable Long id,
                                              @Valid @RequestBody(required = false) EvalScopeExportReq req) {
        return Result.ok(bridge.export(id, req == null ? new EvalScopeExportReq() : req));
    }
}

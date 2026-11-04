package com.agenthub.workflow.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.workflow.api.dto.WorkflowRunDetailResp;
import com.agenthub.workflow.api.dto.WorkflowRunSummaryResp;
import com.agenthub.workflow.api.dto.WorkflowRunTrialReq;
import com.agenthub.workflow.application.WorkflowRunAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作流试跑接口。所属域：workflow。
 * <p>路径：{@code /api/workflow/runs}；权限 {@code workflow:run:trial}。
 * <p>控制器零业务：租户、release 绑定、调度都在 AppService。
 * <p>实时性：本刀用前端轮询 GET 详情（画布高亮），不提供 SSE。
 */
@RestController
@RequestMapping("/api/workflow/runs")
public class WorkflowRunController {

    private final WorkflowRunAppService service;

    /**
     * @param service 试跑用例
     */
    public WorkflowRunController(WorkflowRunAppService service) {
        this.service = service;
    }

    /**
     * 发起试跑：同步推进到终态或人工卡点后返回详情。
     *
     * @param req releaseId 或 workflowId + input
     * @return run 详情
     */
    @SaCheckPermission(WorkflowPermissions.RUN_TRIAL)
    @PostMapping("/trial")
    public Result<WorkflowRunDetailResp> trial(@Valid @RequestBody WorkflowRunTrialReq req) {
        return Result.ok(service.toResp(service.trial(req)));
    }

    /**
     * 查询 run 详情（含节点状态与 OPEN 人工待办）。
     *
     * @param id run 主键
     * @return 详情
     */
    @SaCheckPermission(WorkflowPermissions.RUN_TRIAL)
    @GetMapping("/{id:\\d+}")
    public Result<WorkflowRunDetailResp> get(@PathVariable Long id) {
        return Result.ok(service.toResp(service.get(id)));
    }

    /**
     * 取消 run。已终态幂等返回。
     *
     * @param id run 主键
     * @return 取消后的详情
     */
    @SaCheckPermission(WorkflowPermissions.RUN_TRIAL)
    @PostMapping("/{id:\\d+}/cancel")
    public Result<WorkflowRunDetailResp> cancel(@PathVariable Long id) {
        return Result.ok(service.toResp(service.cancel(id)));
    }

    /**
     * 某工作流最近 10 次 run。
     *
     * @param workflowId 定义 id
     * @return 摘要列表
     */
    @SaCheckPermission(WorkflowPermissions.RUN_TRIAL)
    @GetMapping
    public Result<List<WorkflowRunSummaryResp>> recent(@RequestParam Long workflowId) {
        return Result.ok(service.recent(workflowId).stream().map(WorkflowRunSummaryResp::of).toList());
    }
}

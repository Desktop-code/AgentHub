package com.agenthub.workflow.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.workflow.api.dto.WorkflowHumanActReq;
import com.agenthub.workflow.api.dto.WorkflowHumanTaskItemResp;
import com.agenthub.workflow.api.dto.WorkflowRunDetailResp;
import com.agenthub.workflow.application.WorkflowHumanTaskAppService;
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
 * 人工待办接口。所属域：workflow。
 * <p>路径：{@code /api/workflow/human-tasks}；权限 {@code workflow:human:act}。
 * <p>approve / reject 都同步续跑，返回最新 run 详情，前端直接刷新画布高亮；
 * 列表接口只做导航（run / 工作流 / 节点 / 规则 / 到期），不返回表单与结果正文。
 */
@RestController
@RequestMapping("/api/workflow/human-tasks")
public class WorkflowHumanTaskController {

    private final WorkflowHumanTaskAppService service;
    private final WorkflowRunAppService runService;

    /**
     * @param service    人工待办用例
     * @param runService 详情 DTO 转换
     */
    public WorkflowHumanTaskController(WorkflowHumanTaskAppService service, WorkflowRunAppService runService) {
        this.service = service;
        this.runService = runService;
    }

    /**
     * 待办中心列表。
     *
     * <p>{@code mine=1} 时服务端按当前用户过滤「此刻可处理」的 OPEN 待办
     * （规则命中 + run 仍等待人工），忽略 status；{@code mine=0/缺省} 时按 status 过滤。
     *
     * @param status OPEN / DONE / EXPIRED；空表示全部
     * @param mine   1=只看待我处理；缺省 0
     * @return 待办行（OPEN 优先，按待办 id 倒序）
     */
    @SaCheckPermission(WorkflowPermissions.HUMAN_ACT)
    @GetMapping
    public Result<List<WorkflowHumanTaskItemResp>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int mine) {
        return Result.ok(service.list(mine == 1, status));
    }

    /**
     * 通过人工待办并续跑。
     *
     * @param id  待办 id
     * @param req 备注
     * @return 最新 run 详情
     */
    @SaCheckPermission(WorkflowPermissions.HUMAN_ACT)
    @PostMapping("/{id:\\d+}/approve")
    public Result<WorkflowRunDetailResp> approve(@PathVariable Long id,
                                                 @Valid @RequestBody(required = false) WorkflowHumanActReq req) {
        String note = req == null ? null : req.getNote();
        return Result.ok(runService.toResp(service.approve(id, note)));
    }

    /**
     * 驳回人工待办。默认整 run 失败；画了 ERROR 边则走错误边。
     *
     * @param id  待办 id
     * @param req 驳回原因
     * @return 最新 run 详情
     */
    @SaCheckPermission(WorkflowPermissions.HUMAN_ACT)
    @PostMapping("/{id:\\d+}/reject")
    public Result<WorkflowRunDetailResp> reject(@PathVariable Long id,
                                                @Valid @RequestBody(required = false) WorkflowHumanActReq req) {
        String note = req == null ? null : req.getNote();
        return Result.ok(runService.toResp(service.reject(id, note)));
    }
}

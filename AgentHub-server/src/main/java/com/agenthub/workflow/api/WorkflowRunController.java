package com.agenthub.workflow.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.workflow.api.dto.WorkflowRunDetailResp;
import com.agenthub.workflow.api.dto.WorkflowRunLiveCardResp;
import com.agenthub.workflow.api.dto.WorkflowRunReplyReq;
import com.agenthub.workflow.api.dto.WorkflowRunSummaryResp;
import com.agenthub.workflow.api.dto.WorkflowRunTrialReq;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.application.WorkflowTrialReplyAppService;
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
 * <p>实时性：试跑落库后立即返回；运行中前端读 {@code GET /{id}/live-card} 的按 run 直播快照，
 * 断开或过期时退回 {@code GET /{id}} 现算的已落库卡片。不使用 SSE，也不绑工作台会话。
 */
@RestController
@RequestMapping("/api/workflow/runs")
public class WorkflowRunController {

    private final WorkflowRunAppService service;
    private final WorkflowTrialReplyAppService replies;
    private final WorkflowRunResponses responses;

    /**
     * @param service   试跑用例
     * @param replies   按 run 回复（不经过工作台会话）
     * @param responses 详情响应（附卡片视图）
     */
    public WorkflowRunController(WorkflowRunAppService service, WorkflowTrialReplyAppService replies,
                                 WorkflowRunResponses responses) {
        this.service = service;
        this.replies = replies;
        this.responses = responses;
    }

    /**
     * 发起试跑：run 落库后立即返回，引擎在后台继续。
     *
     * @param req releaseId 或 workflowId + input
     * @return 进行中的 run 详情（含卡片视图）
     */
    @SaCheckPermission(WorkflowPermissions.RUN_TRIAL)
    @PostMapping("/trial")
    public Result<WorkflowRunDetailResp> trial(@Valid @RequestBody WorkflowRunTrialReq req) {
        return responses.ok(service.trial(req));
    }

    /**
     * 把一句回复送回这一条试跑并续跑。不新建 run，不写工作台会话。
     *
     * <p>权限与发起试跑相同（{@code workflow:run:trial}）。
     *
     * @param id  run 主键
     * @param req 回复正文
     * @return 续跑后的详情（含卡片视图）
     */
    @SaCheckPermission(WorkflowPermissions.RUN_TRIAL)
    @PostMapping("/{id:\\d+}/reply")
    public Result<WorkflowRunDetailResp> reply(@PathVariable Long id, @Valid @RequestBody WorkflowRunReplyReq req) {
        return responses.ok(replies.reply(id, req.getReply()));
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
        return responses.ok(service.get(id));
    }

    /**
     * 读取一次按 run 的调试直播快照（运行中短轮询）。
     *
     * <p>只对 {@code trigger_type=TRIAL} 且没有工作台会话的运行生效；没有可用直播时返回
     * {@code live=false}，调用方退回详情现算。权限与试跑相同。
     *
     * @param id run 主键
     * @return 直播快照或空响应
     */
    @SaCheckPermission(WorkflowPermissions.RUN_TRIAL)
    @GetMapping("/{id:\\d+}/live-card")
    public Result<WorkflowRunLiveCardResp> liveCard(@PathVariable Long id) {
        return responses.live(id);
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
        return responses.ok(service.cancel(id));
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
        return Result.ok(service.recent(workflowId).stream().map(service::toSummary).toList());
    }
}

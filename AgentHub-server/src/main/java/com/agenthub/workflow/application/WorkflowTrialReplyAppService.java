package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 调试抽屉按 run 回复并续跑。所属域：workflow 的 application。
 *
 * <p>职责：校验「这一条是当前用户自己的试跑」之后，调用
 * {@link WorkflowRunAppService#resumeWithReplyInBackground}：回复与状态迁移落库后接口立即返回，
 * 引擎交给试跑后台调度继续。不新建 run，不写工作台会话，不改发布版本，
 * 也不走 {@code findWaitingReplyRun(sessionId)}。
 *
 * <p>为什么不再在这里同步跑引擎：一次续跑可能跑几十秒，请求线程等不到返回时，
 * 前端只能把「停止」按请求在飞锁住，用户没法在运行中停下。
 *
 * <p>直播（{@code GET /api/workflow/runs/{id}/live-card}）由
 * {@link WorkflowTrialDispatcher} 在后台线程挂载与收口，本类不再自行挂载。
 *
 * <p>工作台按会话续跑的路径不经过本类。
 */
@Service
public class WorkflowTrialReplyAppService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTrialReplyAppService.class);

    private final WorkflowRunRepository runs;
    private final WorkflowRunAppService workflowRuns;
    private final WorkflowTenantProvider tenantProvider;
    private final OperatorContext operatorContext;

    /**
     * @param runs            运行仓储（校验触发类型与归属）
     * @param workflowRuns    已有的按 run 续跑（后台版）
     * @param tenantProvider  当前租户
     * @param operatorContext 当前操作人
     */
    public WorkflowTrialReplyAppService(WorkflowRunRepository runs, WorkflowRunAppService workflowRuns,
                                        WorkflowTenantProvider tenantProvider, OperatorContext operatorContext) {
        this.runs = runs;
        this.workflowRuns = workflowRuns;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
    }

    /**
     * 把一句回复送回指定试跑 run，并从挂起处继续（引擎在后台）。
     *
     * @param runId 试跑 run 主键
     * @param reply 用户下一句或选项文案（非空）
     * @return 续跑请求返回时的详情（状态已回到 RUNNING，卡片继续由直播补齐）
     * @throws BizException 不存在 / 跨租户 / 不是自己的试跑 / 已不在等待回复
     */
    public WorkflowRunDetail reply(Long runId, String reply) {
        long operatorId = operatorContext.requireUserId();
        long tenantId = tenantProvider.currentTenantId();
        WorkflowRun run = runs.findById(runId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "运行实例不存在"));
        if (run.getTenantId() == null || run.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行实例不存在");
        }
        // 只续调试自己发起的 TRIAL。工作台 run 仍由会话入口续，避免两条路径抢同一条
        if (run.getWorkbenchSessionId() != null || !isTrial(run.getTriggerType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "这条运行不是调试记录，不能在这里继续");
        }
        if (run.getTriggeredBy() != null && run.getTriggeredBy() != operatorId) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能继续自己发起的调试");
        }
        log.debug("调试续跑提交 runId={}", runId);
        return workflowRuns.resumeWithReplyInBackground(runId, reply, operatorId);
    }

    /**
     * 是否控制台试跑。未知触发类型一律不当成试跑，避免 valueOf 把脏数据抛成 500。
     *
     * @param raw 库里的触发类型
     * @return true 表示 TRIAL
     */
    private static boolean isTrial(String raw) {
        return raw != null && WorkflowTriggerType.TRIAL.name().equalsIgnoreCase(raw.trim());
    }
}

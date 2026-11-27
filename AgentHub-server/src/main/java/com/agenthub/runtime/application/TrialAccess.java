package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.EntryChannel;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 试跑会话 / 画布调试运行是否属于当前平台用户。所属域：runtime。
 * 只认 user_id，管理员也不能凭 sessionId 看别人的试跑。一期不做租户隔离。
 *
 * <p>两个口径：工作台有会话，按 {@code agent_run_session.user_id} 认；
 * 画布「调试」的试跑没有会话，按 {@code workflow_run} 的发起人认（见
 * {@link #requireWorkspace(Long)}）。都只放行本人。
 */
@Component
public class TrialAccess {

    private final RunSessionRepository sessions;
    private final WorkflowRunRepository workflowRuns;
    private final OperatorContext operatorContext;

    /**
     * @param sessions        会话
     * @param workflowRuns    工作流运行（画布调试没有会话，按运行发起人认归属）
     * @param operatorContext 当前登录人
     */
    public TrialAccess(RunSessionRepository sessions, WorkflowRunRepository workflowRuns,
                       OperatorContext operatorContext) {
        this.sessions = sessions;
        this.workflowRuns = workflowRuns;
        this.operatorContext = operatorContext;
    }

    /**
     * @param id 会话主键
     * @return 当前用户自己的会话
     * @throws BizException 不存在，或 user_id 不是当前用户
     */
    public RunSession require(Long id) {
        RunSession session = sessions.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "会话不存在"));
        long userId = operatorContext.requireUserId();
        if (session.getUserId() == null || !session.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能查看别人的会话");
        }
        return session;
    }

    /**
     * 工作区文件下载的归属校验：先认工作台会话，再认画布调试的试跑运行。
     *
     * <p>为什么除了会话还要认运行：画布「调试」的试跑没有 {@code agent_run_session}
     * （工作台才建会话），而它的沙箱目录同样是 {@code trial-<runId>}，下载端入参也就是
     * 这个 run 主键。只认会话的话，调试过程区里已经流出的文件行永远下载不了。
     *
     * <p>安全边界与 {@link #require} 同一口径：只放行<b>当前登录用户自己</b>的试跑
     * （{@code triggered_by} = 当前用户、{@code trigger_type=TRIAL}、且不挂工作台会话），
     * 管理员也不能凭 run 主键拿别人的产物。
     *
     * @param id 工作台会话主键，或画布调试的 {@code workflow_run} 主键
     * @throws BizException 会话 / 运行不存在，或不属于当前用户
     */
    public void requireWorkspace(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        long userId = operatorContext.requireUserId();
        // 工作台会话优先：命中就按原来的会话归属口径收口
        Optional<RunSession> session = sessions.findById(id);
        if (session.isPresent()) {
            RunSession found = session.get();
            if (found.getUserId() == null || !found.getUserId().equals(userId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "不能查看别人的会话");
            }
            return;
        }
        WorkflowRun run = workflowRuns.findById(id).orElseThrow(
                () -> new BizException(ErrorCode.NOT_FOUND, "会话不存在"));
        // 只有「画布调试」这一类运行才按 run 命名工作区：工作台运行走会话，应用触发没有平台用户
        boolean ownTrial = run.getTriggeredBy() != null && run.getTriggeredBy().equals(userId)
                && run.getWorkbenchSessionId() == null
                && WorkflowTriggerType.TRIAL.name().equalsIgnoreCase(
                        run.getTriggerType() == null ? "" : run.getTriggerType().trim());
        if (!ownTrial) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能查看别人的运行产物");
        }
    }

    /**
     * 工作台会话校验：必须是当前用户自己的、且入口渠道为 WORKBENCH 的会话。
     *
     * <p>为什么另开一个方法：工作台与调试共用一张会话表，只靠 user_id 的话，
     * 用户可以拿调试会话 id 从工作台接口继续聊，两个入口的权限与列表就串了。
     *
     * @param id 会话主键
     * @return 当前用户自己的工作台会话
     * @throws BizException 不存在、不属于当前用户，或不是工作台会话
     */
    public RunSession requireWorkbench(Long id) {
        RunSession session = require(id);
        if (!EntryChannel.isWorkbench(session.getEntryChannel())) {
            throw new BizException(ErrorCode.FORBIDDEN, "该会话不属于对话入口，请到控制台运行入口继续");
        }
        return session;
    }
}

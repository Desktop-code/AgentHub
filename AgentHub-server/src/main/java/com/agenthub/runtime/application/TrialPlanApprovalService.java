package com.agenthub.runtime.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.RunStatus;
import java.util.Optional;

/**
 * 计划批准与待批查询。所属域：runtime（会话运行）。
 *
 * <p>从 {@code TrialRunAppService} 搬出：本类只做「批准槽 / 待批登记」的读写与轻量响应，
 * 不跑模型、不落时间线、不碰直播快照。单 Agent 计划的「批准后继续执行」仍留在
 * {@code TrialRunAppService}：它共用同一轮的单飞闸门、trace 与成功 / 失败收尾，
 * 搬走会拆散「同一轮只收口一次」的边界。
 *
 * <p>口径保持不变：
 * <ul>
 *   <li>待批计划与子计划都按「会话归属校验 + 登记项自身 userId」双重校验；</li>
 *   <li>批准 / 拒绝只动登记表，由等待中的子线程继续或被跳过；事件顺序与文案不变；</li>
 *   <li>响应字段（sessionId / status / planPending / childPlanApproved / childRouteKey / assistantPreview）不变。</li>
 * </ul>
 */
public class TrialPlanApprovalService {

    /** 会话归属校验：只允许当前用户操作自己的会话。 */
    private final TrialAccess access;

    /** 当前用户：登记项归属与操作人一致才允许处理。 */
    private final OperatorContext operatorContext;

    /** 单 Agent 待批准计划登记表。 */
    private final PlanPendingRegistry pendingPlans;

    /** 编排根下的子计划待批登记与「同时仅 1 个」批准槽。 */
    private final ChildPlanApprovalRegistry childApprovals;

    /** 只记动作，不记对话。 */
    private final OperLogWriter operLogWriter;

    /**
     * @param access          会话归属校验
     * @param operatorContext 当前用户
     * @param pendingPlans    待批准计划登记表
     * @param childApprovals  子计划待批登记
     * @param operLogWriter   操作日志
     */
    public TrialPlanApprovalService(TrialAccess access, OperatorContext operatorContext,
                                    PlanPendingRegistry pendingPlans, ChildPlanApprovalRegistry childApprovals,
                                    OperLogWriter operLogWriter) {
        this.access = access;
        this.operatorContext = operatorContext;
        this.pendingPlans = pendingPlans;
        this.childApprovals = childApprovals;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 查询该会话是否有待批准计划。前端刷新后靠它恢复「批准 / 取消」按钮。
     *
     * @param sessionId 会话
     * @return 有待批准计划时为计划视图（PENDING）；否则为空
     */
    public Optional<PlanView> pendingPlan(Long sessionId) {
        access.require(sessionId);
        long userId = operatorContext.requireUserId();
        // 登记项自带归属用户：即便会话校验通过，也不把别人的计划内容回显出来
        return pendingPlans.peek(sessionId)
                .filter(item -> item.userId() == userId)
                .map(item -> new PlanView(item.planId(), PlanState.PENDING, item.steps()));
    }

    /**
     * 编排根下的子计划待批准：批准并唤醒等待中的子线程。
     *
     * <p>与单 Agent 的批准分开：这条路径不续跑任何根侧步骤，
     * 只是把控制权还给「已规划未执行」的 P&E 子线程；根终答仍由 Router 等齐后统一汇总。
     *
     * @param sessionId 根会话
     * @param routeKey  待批子的路由键；空表示当前唯一待批
     * @return 轻量确认（无新事件，根 SSE 会继续推后续增量）
     * @throws BizException 没有待批子计划、已失效，或不属于当前用户
     */
    public MessageResp approveChildPlan(Long sessionId, String routeKey) {
        return decideChildPlan(sessionId, routeKey, true);
    }

    /**
     * 编排根下的子计划待批准：拒绝。该子以失败占位继续，其它子不受影响。
     *
     * @param sessionId 根会话
     * @param routeKey  待批子的路由键；空表示当前唯一待批
     * @return 轻量确认
     * @throws BizException 没有待批子计划、已失效，或不属于当前用户
     */
    public MessageResp rejectChildPlan(Long sessionId, String routeKey) {
        return decideChildPlan(sessionId, routeKey, false);
    }

    /**
     * 查当前待批的子计划。刷新页面后前端靠它恢复「批准 / 拒绝」按钮；
     * 与根 SSE 的 {@code ma_child_plan_pending} 载荷同构。
     *
     * @param sessionId 根会话
     * @return 有待批子时为其视图；否则为空
     */
    public Optional<ChildPlanPendingView> pendingChildPlan(Long sessionId) {
        access.require(sessionId);
        long userId = operatorContext.requireUserId();
        return childApprovals.peek(sessionId)
                .filter(item -> item.plan().userId() == userId)
                .map(item -> new ChildPlanPendingView(item.routeKey(), item.childAgentId(), item.childAgentName(),
                        item.plan().planId(), PlanState.PENDING.name(), item.plan().steps(),
                        ChildPlanPendingView.RUN_STATE_PAUSED));
    }

    /**
     * 批准 / 拒绝子计划的共用落库与校验。
     *
     * @param sessionId 根会话
     * @param routeKey  路由键，可空
     * @param approved  true＝批准，false＝拒绝
     * @return 轻量确认
     */
    private MessageResp decideChildPlan(Long sessionId, String routeKey, boolean approved) {
        access.require(sessionId);
        long userId = operatorContext.requireUserId();
        ChildPlanApprovalRegistry.PendingChildPlan pending = childApprovals.find(sessionId, routeKey)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "没有待批准的子计划（可能已过期或已被处理）"));
        // 归属校验：会话校验只证明「库里的会话是你的」，这里再核登记项自身的 userId
        if (pending.plan().userId() != userId) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权处理其它用户的子计划");
        }
        boolean decided = approved
                ? childApprovals.approve(sessionId, routeKey)
                : childApprovals.reject(sessionId, routeKey);
        if (!decided) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该子计划已失效（可能已超时或被取消），请重新提问");
        }
        operLogWriter.write("runtime", approved ? "childPlanApprove" : "childPlanReject", "agent_run_session",
                String.valueOf(sessionId),
                "子专家「" + pending.childAgentName() + "」计划" + (approved ? "已批准" : "已拒绝"));
        MessageResp resp = new MessageResp();
        resp.setSessionId(sessionId);
        resp.setStatus(RunStatus.SUCCESS.name());
        resp.setPlanPending(false);
        resp.setChildPlanApproved(approved);
        resp.setChildRouteKey(pending.routeKey());
        resp.setAssistantPreview(approved ? "已批准子计划，继续执行" : "已拒绝子计划，该子已跳过");
        return resp;
    }
}

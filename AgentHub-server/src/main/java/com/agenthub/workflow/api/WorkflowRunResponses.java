package com.agenthub.workflow.api;

import com.agenthub.common.api.Result;
import com.agenthub.runtime.application.WorkflowDebugCardQuery;
import com.agenthub.runtime.application.WorkflowDebugLiveCards;
import com.agenthub.workflow.api.dto.WorkflowNodeRunResp;
import com.agenthub.workflow.api.dto.WorkflowRunDetailResp;
import com.agenthub.workflow.api.dto.WorkflowRunLiveCardResp;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import org.springframework.stereotype.Component;

/**
 * 把 run 详情收成带卡片视图的响应。所属域：workflow 的 api。
 *
 * <p>试跑、取消、按 run 回复、人工处理后都走这里，避免各控制器各自拼卡片。
 * 卡片现算失败时仍返回节点状态，不把接口打成失败。
 *
 * <p>调试直播也在这里收口：按 run 读 runtime 的 {@link WorkflowDebugLiveCards}，
 * 卡片仍用同一个 {@link WorkflowDebugCardQuery} 出载荷，不另造第二种视图。
 */
@Component
public class WorkflowRunResponses {

    private final WorkflowRunAppService runs;
    private final WorkflowDebugCardQuery cards;
    private final WorkflowDebugLiveCards liveCards;
    private final WorkflowTenantProvider tenantProvider;

    /**
     * @param runs           详情 DTO
     * @param cards          与首页同形的卡片载荷
     * @param liveCards      按 run 隔离的调试直播快照
     * @param tenantProvider 当前租户（直播快照做同租户校验）
     */
    public WorkflowRunResponses(WorkflowRunAppService runs, WorkflowDebugCardQuery cards,
                                WorkflowDebugLiveCards liveCards, WorkflowTenantProvider tenantProvider) {
        this.runs = runs;
        this.cards = cards;
        this.liveCards = liveCards;
        this.tenantProvider = tenantProvider;
    }

    /**
     * @param detail 聚合；可空时卡片为空
     * @return 成功响应
     */
    public Result<WorkflowRunDetailResp> ok(WorkflowRunDetail detail) {
        WorkflowRunDetailResp resp = runs.toResp(detail);
        if (detail != null && detail.getRun() != null) {
            resp.setCard(cards.payload(detail));
        }
        return Result.ok(resp);
    }

    /**
     * 读取一次按 run 的调试直播快照。
     *
     * <p>没有直播、已过期或不是当前租户的 run 一律返回 {@code live=false}：
     * 前端据此退回详情现算，不暴露别的租户是否存在这条运行。
     *
     * @param runId 运行主键
     * @return 直播快照或空响应
     */
    public Result<WorkflowRunLiveCardResp> live(Long runId) {
        if (runId == null) {
            return Result.ok(WorkflowRunLiveCardResp.empty());
        }
        WorkflowDebugLiveCards.Snapshot snapshot = liveCards.snapshot(runId);
        if (snapshot == null || snapshot.tenantId() == null
                || snapshot.tenantId().longValue() != tenantProvider.currentTenantId()) {
            return Result.ok(WorkflowRunLiveCardResp.empty());
        }
        WorkflowRunLiveCardResp resp = new WorkflowRunLiveCardResp();
        resp.setLive(true);
        resp.setStatus(snapshot.status());
        resp.setErrorSummary(snapshot.errorSummary());
        resp.setCard(cards.payloadOf(snapshot.view()));
        for (WorkflowNodeRun row : snapshot.rows()) {
            WorkflowNodeRunResp item = WorkflowNodeRunResp.of(row);
            if (item != null) {
                resp.getNodeRuns().add(item);
            }
        }
        return Result.ok(resp);
    }
}

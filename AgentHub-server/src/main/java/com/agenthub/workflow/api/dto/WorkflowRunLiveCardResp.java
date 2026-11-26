package com.agenthub.workflow.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 调试试跑的直播卡片快照响应。所属域：workflow 的 api。
 *
 * <p>用途：调试抽屉在运行中以短周期读这里，卡片与节点状态都来自同一条按 run 隔离的直播，
 * 不再等 1.5 秒的详情轮询。{@code live=false} 表示这次没有可用直播（未接入 / 已过期 /
 * 不属于当前租户），调用方应退回 {@code GET /api/workflow/runs/{id}} 的已落库卡片。
 *
 * <p>形状与详情接口保持同一套：{@code card} 仍是 {@code WorkflowRunJson.payload} 的输出，
 * 前端直接交给 {@code WorkflowRunCard}，不在这里拼第二种卡片。
 */
@Data
public class WorkflowRunLiveCardResp {

    /** 是否有按 run 的直播；false 时 card 为空，调用方走详情兜底 */
    private boolean live;

    /** run 当前状态（CREATED / RUNNING / WAITING_REPLY / PAUSED_HUMAN / SUCCESS / FAILED / CANCELLED） */
    private String status;

    /** 与首页工作流卡片同一份载荷；直播尚未出第一帧时可为空 */
    private Object card;

    /** 节点运行态，与详情接口同一字段口径；供画布高亮与跟随 */
    private List<WorkflowNodeRunResp> nodeRuns = new ArrayList<>();

    /** 失败 / 取消摘要；可空 */
    private String errorSummary;

    /**
     * 「没有直播」的响应。
     *
     * @return live=false 的空响应
     */
    public static WorkflowRunLiveCardResp empty() {
        return new WorkflowRunLiveCardResp();
    }
}

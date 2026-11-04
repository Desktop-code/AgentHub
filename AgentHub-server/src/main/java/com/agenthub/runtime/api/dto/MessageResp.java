package com.agenthub.runtime.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮试跑的同步结果。所属域：runtime。失败时 status=FAILED，events 里有 error。
 */
@Data
public class MessageResp {

    private Long sessionId;
    private String status;
    private String assistantPreview;
    private String errorSummary;
    private List<EventResp> events = new ArrayList<>();
    /**
     * 计划执行模式下本轮停在「等待批准」。
     * 为 true 时 events 里只有 PLAN（state=PENDING），没有 assistant；
     * 前端据此渲染批准 / 取消按钮，而不是显示一条空回复。
     */
    private boolean planPending;

    /**
     * 子计划批准 / 拒绝的轻量确认：true 表示这次调用确实唤醒 / 拒绝了一个待批子。
     * 根侧没有新落库事件，因此 events 为空、assistantPreview 只是一句提示。
     */
    private boolean childPlanApproved;

    /** 本次处理的子路由键（回显，便于前端清掉对应待批卡片） */
    private String childRouteKey;
}

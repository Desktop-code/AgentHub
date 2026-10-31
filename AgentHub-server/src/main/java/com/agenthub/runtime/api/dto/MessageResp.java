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
}

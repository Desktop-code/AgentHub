package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.RunEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一轮有没有写完。所属域：runtime。
 * <p>
 * 口径与前端 {@code hasOpenTurn} 一致：最后一条 user 之后还没有 assistant / error，
 * 这一轮才算没写完。计划停在待批准、已取消或已完成时，本轮不会再有助手正文，也算写完。
 * 不读会话 status：多轮正常结束后会话仍是 RUNNING，不能拿它判断这一轮有没有中断。
 */
public final class TurnStillOpen {

    private TurnStillOpen() {
    }

    /**
     * @param events 同一会话的事件，顺序不限；可空
     * @return true 表示最后一轮还没写完
     */
    public static boolean of(List<RunEvent> events) {
        if (events == null || events.isEmpty()) {
            return false;
        }
        List<RunEvent> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingInt(TurnStillOpen::seqOf));
        boolean open = false;
        for (RunEvent item : ordered) {
            if (item == null || item.getEventType() == null) {
                continue;
            }
            String type = item.getEventType();
            if (EventType.USER.code().equalsIgnoreCase(type)) {
                open = true;
            } else if (EventType.ASSISTANT.code().equalsIgnoreCase(type)
                    || EventType.ERROR.code().equalsIgnoreCase(type)) {
                open = false;
            } else if (EventType.PLAN.code().equalsIgnoreCase(type) && planClosed(item.getContentText())) {
                // 待批准 / 用户取消计划 / 计划已完成：这一轮没有后续正文
                open = false;
            }
        }
        return open;
    }

    /**
     * 计划事件是否已经把这一轮收住。执行中和失败仍可能跟着步骤或终答，不算收住。
     *
     * @param content 计划事件正文
     * @return true 表示不应再等助手正文
     */
    private static boolean planClosed(String content) {
        PlanState state = PlanJson.parsePlan(content).map(PlanView::state).orElse(null);
        return state == PlanState.PENDING || state == PlanState.REJECTED || state == PlanState.DONE;
    }

    private static int seqOf(RunEvent event) {
        if (event == null || event.getSeq() == null) {
            return 0;
        }
        return event.getSeq();
    }
}

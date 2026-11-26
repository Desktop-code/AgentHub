package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 调试直播快照的窄测。所属域：runtime 的 application。
 *
 * <p>钉住三件事：按 run 隔离读写；收口后迟到的帧不能把终态改回运行中；
 * 挂载失败可以用 discard 撤掉，让前端退回详情而不是停在一份永不更新的快照上。
 *
 * <p>全部在内存里跑，不连库、不出站、不打模型。
 */
class WorkflowDebugLiveCardsTest {

    /**
     * 新建一条 run 的直播：先没有卡片，发布一帧后读得到，收口后保留终态。
     */
    @Test
    @DisplayName("按 run 发布与收口：终态卡片保留，迟到的运行中帧被忽略")
    void publishAndFinish() {
        WorkflowDebugLiveCards cards = new WorkflowDebugLiveCards();
        long runId = 21L;
        cards.begin(runId, 7L, null, List.of());

        WorkflowDebugLiveCards.Snapshot opened = cards.snapshot(runId);
        assertNotNull(opened);
        assertNull(opened.view());
        assertEquals(7L, opened.tenantId());

        WorkflowRunView running = view("RUNNING", "正在思考");
        cards.publish(runId, running, List.of(nodeRun(runId, "RUNNING")));
        WorkflowDebugLiveCards.Snapshot streaming = cards.snapshot(runId);
        assertNotNull(streaming);
        assertEquals("RUNNING", streaming.status());
        assertEquals("正在思考", streaming.view().answer());
        assertEquals(1, streaming.rows().size());

        WorkflowRunView done = view("SUCCESS", "已完成");
        cards.finish(runId, done, List.of(nodeRun(runId, "SUCCESS")), "SUCCESS", null);
        WorkflowDebugLiveCards.Snapshot settled = cards.snapshot(runId);
        assertNotNull(settled);
        assertEquals("SUCCESS", settled.status());
        assertEquals("已完成", settled.view().answer());

        // 收口后引擎线程不该再写回来：迟到的运行中帧被忽略
        cards.publish(runId, view("RUNNING", "又跑起来了"), List.of());
        WorkflowDebugLiveCards.Snapshot afterLateFrame = cards.snapshot(runId);
        assertNotNull(afterLateFrame);
        assertEquals("SUCCESS", afterLateFrame.status());
        assertEquals("已完成", afterLateFrame.view().answer());
    }

    /**
     * 收口时卡片为空：保留最后一帧，不把已经流出来的内容清掉。
     */
    @Test
    @DisplayName("收口帧缺卡片时保留最后一帧")
    void finishKeepsLastView() {
        WorkflowDebugLiveCards cards = new WorkflowDebugLiveCards();
        long runId = 22L;
        cards.begin(runId, 7L, null, List.of());
        cards.publish(runId, view("RUNNING", "已流出的正文"), List.of());
        cards.finish(runId, null, List.of(), "CANCELLED", "用户停止");

        WorkflowDebugLiveCards.Snapshot snapshot = cards.snapshot(runId);
        assertNotNull(snapshot);
        assertEquals("CANCELLED", snapshot.status());
        assertEquals("用户停止", snapshot.errorSummary());
        assertEquals("已流出的正文", snapshot.view().answer());
    }

    /**
     * 挂载失败丢弃：读侧拿不到快照，前端据此退回详情现算。
     */
    @Test
    @DisplayName("挂载失败 discard 后读不到直播，退回详情")
    void discardRemovesEntry() {
        WorkflowDebugLiveCards cards = new WorkflowDebugLiveCards();
        long runId = 23L;
        cards.begin(runId, 7L, null, List.of());
        assertNotNull(cards.snapshot(runId));
        cards.discard(runId);
        assertNull(cards.snapshot(runId));
    }

    /** @return 只带正文与状态的卡片视图，足够验证快照存取 */
    private static WorkflowRunView view(String status, String answer) {
        return new WorkflowRunView("演示", "demo", status, answer, List.of());
    }

    /** @return 一行可复制的节点运行态 */
    private static WorkflowNodeRun nodeRun(long runId, String status) {
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setRunId(runId);
        row.setNodeId("n1");
        row.setNodeCode("ask");
        row.setNodeType("AGENT");
        row.setAttempt(1);
        row.setStatus(status);
        return row;
    }
}

package com.agenthub.workflow.support;

import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.service.WorkflowTraceWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存埋点接收器。所属域：workflow 的 test support。
 *
 * <p>用途：给引擎用例做替身——要么记录调用（断言「开跑 / 每节点 / 收尾」顺序），
 * 要么在 {@code failAll=true} 时每次调用都抛异常（断言埋点失败不影响主跑）。
 * 不连库、不出站。
 */
public class RecordingTraceWriter implements WorkflowTraceWriter {

    private final boolean failAll;
    private final List<String> events = new ArrayList<>();
    private final List<String> openedNodes = new ArrayList<>();
    private final List<String> closedNodes = new ArrayList<>();

    /**
     * 正常记录模式。
     */
    public RecordingTraceWriter() {
        this(false);
    }

    /**
     * @param failAll true 表示每次调用都抛 RuntimeException，用于降级用例
     */
    public RecordingTraceWriter(boolean failAll) {
        this.failAll = failAll;
    }

    @Override
    public void beginRun(WorkflowRun run) {
        failIfNeeded();
        events.add("begin:" + run.getId());
    }

    @Override
    public void beginNode(WorkflowRun run, WorkflowNode node) {
        failIfNeeded();
        openedNodes.add(node.getCode());
    }

    @Override
    public void endNode(WorkflowRun run, WorkflowNode node, WorkflowNodeRun row) {
        failIfNeeded();
        closedNodes.add(node.getCode() + ":" + row.getStatus());
    }

    @Override
    public void finishRun(WorkflowRun run) {
        failIfNeeded();
        events.add("finish:" + run.getStatus());
    }

    private void failIfNeeded() {
        if (failAll) {
            throw new IllegalStateException("mock：埋点写入失败");
        }
    }

    /**
     * @return 开跑 / 收尾事件，形如 {@code begin:1}、{@code finish:SUCCESS}
     */
    public List<String> events() {
        return events;
    }

    /**
     * @return 实际开过节点的编码，按执行顺序
     */
    public List<String> openedNodes() {
        return openedNodes;
    }

    /**
     * @return 收口记录，形如 {@code answer:SUCCESS}
     */
    public List<String> closedNodes() {
        return closedNodes;
    }
}

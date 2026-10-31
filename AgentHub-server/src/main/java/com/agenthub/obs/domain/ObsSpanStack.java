package com.agenthub.obs.domain;

/**
 * 单条子执行链的父指针上下文。所属域：obs。
 * 并行时每工作线程持有独立 stack，并由 MeteringModel / TrialSpanSink 捕获引用；
 * AgentScope 回调换线程后仍读同一对象，避免 ThreadLocal 被冲掉导致 parent 变 null。
 */
public final class ObsSpanStack {

    private volatile Long parentId;
    private volatile String parentStepNo;
    private volatile Long stepAgentId;

    /**
     * @return 当前父行自增 id；根或未 bind 时为 null
     */
    public Long parentId() {
        return parentId;
    }

    /**
     * @return 父步的 step_no，供子步发号；可空
     */
    public String parentStepNo() {
        return parentStepNo;
    }

    /**
     * @return 本子链最近显式带上的 Agent；工具步可沿用
     */
    public Long stepAgentId() {
        return stepAgentId;
    }

    /**
     * 接下来的 LLM / TOOL 挂到该父行下。
     *
     * @param parentId     父行 id，可空表示回到根
     * @param parentStepNo 父步展示号，可空
     */
    public void bind(Long parentId, String parentStepNo) {
        this.parentId = parentId;
        this.parentStepNo = parentStepNo;
    }

    /**
     * 记录本子链 Agent，供 TOOL 等未显式传 agentId 的步骤沿用。
     *
     * @param agentId Agent 主键，可空则不改
     */
    public void rememberAgent(Long agentId) {
        if (agentId != null) {
            this.stepAgentId = agentId;
        }
    }

    /**
     * 清空父指针与 Agent（对象仍可被回调持有，内容被清后新开步会挂根）。
     */
    public void clear() {
        this.parentId = null;
        this.parentStepNo = null;
        this.stepAgentId = null;
    }
}

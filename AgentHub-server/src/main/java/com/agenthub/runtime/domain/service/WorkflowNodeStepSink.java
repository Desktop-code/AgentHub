package com.agenthub.runtime.domain.service;

/**
 * 工作流 AGENT 节点执行过程中的增量落点。所属域：runtime。
 *
 * <p>为什么需要它：工作流卡以前只在节点完成时追加一段正文，节点自己的思考、
 * 工具调用和运行中正文没有落点，只能堆在整张卡下面。本接口把「子 Agent 的
 * 实时过程」按节点编码交回卡片投影，由投影挂到对应节点内部。
 *
 * <p>边界与线程：
 * <ul>
 *   <li>调用方是 {@link WorkflowAgentFileScope} 包装后的 {@link TrialProgress}，
 *       思考 / 工具 / 正文通常在同一执行线程上回调；文件回调可能在工具线程上；</li>
 *   <li>实现方只做本节点展示态的收集，<b>不得</b>反向调用引擎或修改运行状态；</li>
 *   <li>内容已经过上游脱敏与截断，实现方不要再拼装模型入参或密钥。</li>
 * </ul>
 */
public interface WorkflowNodeStepSink {

    /**
     * 追加一段节点思考。
     *
     * @param nodeCode 节点编码；空串表示无法归属，实现方可忽略
     * @param delta    思考增量
     */
    void reasoning(String nodeCode, String delta);

    /**
     * 节点里的某个工具开始执行。
     *
     * @param nodeCode 节点编码
     * @param name     工具名
     */
    void toolStart(String nodeCode, String name);

    /**
     * 节点里的某个工具结束。
     *
     * @param nodeCode  节点编码
     * @param name      工具名
     * @param arguments 工具入参（已脱敏）；展示可不用
     * @param result    工具结果（已脱敏、已按展示口径截断）
     * @param success   引擎状态为成功
     */
    void tool(String nodeCode, String name, String arguments, String result, boolean success);

    /**
     * 追加节点正在流出的正文。
     *
     * @param nodeCode 节点编码
     * @param delta    正文增量
     */
    void assistant(String nodeCode, String delta);

    /**
     * 节点写出了一个工作区文件。
     *
     * @param nodeCode 节点编码
     * @param fileName 相对工作区根的文件名
     * @param size     字节数；小于 0 表示未知
     */
    void file(String nodeCode, String fileName, long size);
}

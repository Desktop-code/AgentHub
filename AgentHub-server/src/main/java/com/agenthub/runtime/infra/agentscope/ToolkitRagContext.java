package com.agenthub.runtime.infra.agentscope;

import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

import java.util.HashSet;
import java.util.Set;

/**
 * 给 Toolkit 套上 {@link RagContextAgentTool}。所属域：runtime。
 * 应在配额与必填闸门之后、交给引擎之前调用，确保任意工具回调线程都能读到本轮 Agent。
 */
public final class ToolkitRagContext {

    private ToolkitRagContext() {
    }

    /**
     * 原地替换为带 Agent 上下文的包装。agentId 空或无工具时原样返回。
     *
     * @param toolkit 已装配工具集
     * @param agentId 本轮 Agent
     * @return 同一 toolkit
     */
    public static Toolkit wrap(Toolkit toolkit, Long agentId) {
        return wrap(toolkit, agentId, null, null);
    }

    /**
     * 带上本轮会话与操作者：查询改写出站要按这一轮的会话 / 用户记账，
     * 工具线程读不到请求线程的上下文，只能在组装时随包装带下去。
     *
     * @param toolkit    已装配工具集
     * @param agentId    本轮 Agent
     * @param sessionId  本轮运行会话，可空
     * @param operatorId 平台用户，可空
     * @return 同一 toolkit
     */
    public static Toolkit wrap(Toolkit toolkit, Long agentId, Long sessionId, Long operatorId) {
        if (toolkit == null || agentId == null) {
            return toolkit;
        }
        // 在 ReAct 主线程取好 trace：工具执行在 boundedElastic，那里 ObsTraceHolder 取不到
        ObsTraceScope trace = ObsTraceHolder.current();
        Set<String> names = new HashSet<>(toolkit.getToolNames());
        for (String name : names) {
            AgentTool original = toolkit.getTool(name);
            if (original == null || original instanceof RagContextAgentTool) {
                continue;
            }
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new RagContextAgentTool(original, agentId, sessionId, operatorId, trace));
        }
        return toolkit;
    }
}

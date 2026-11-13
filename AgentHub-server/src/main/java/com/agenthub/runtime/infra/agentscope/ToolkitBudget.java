package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.RunBudget;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

import java.util.HashSet;
import java.util.Set;

/**
 * 给 Toolkit 里已注册工具套上配额包装。所属域：runtime。
 */
public final class ToolkitBudget {

    private ToolkitBudget() {
    }

    /**
     * 原地替换为 {@link BudgetingAgentTool}。无工具时原样返回。
     *
     * @param toolkit 已装配工具集
     * @param budget  本轮配额
     * @return 同一 toolkit
     */
    public static Toolkit wrap(Toolkit toolkit, RunBudget budget) {
        return wrap(toolkit, budget, "");
    }

    /**
     * 同上，并声明本轮专家归属；连续失败计数按该归属计量，专家切换时清零。
     *
     * @param toolkit 已装配工具集
     * @param budget  本轮配额
     * @param owner   本轮专家归属（id / 展示名）；空串表示不区分
     * @return 同一 toolkit
     */
    public static Toolkit wrap(Toolkit toolkit, RunBudget budget, String owner) {
        if (toolkit == null || budget == null) {
            return toolkit;
        }
        Set<String> names = new HashSet<>(toolkit.getToolNames());
        for (String name : names) {
            AgentTool original = toolkit.getTool(name);
            if (original == null || original instanceof BudgetingAgentTool) {
                continue;
            }
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new BudgetingAgentTool(original, budget, owner));
        }
        return toolkit;
    }
}

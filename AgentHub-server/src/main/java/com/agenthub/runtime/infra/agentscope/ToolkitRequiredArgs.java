package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.RequiredToolArgs;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 给 Toolkit 里的工具套上必填参数闸门。所属域：runtime 的 infra。
 * 必须在 {@link ToolkitBudget} 之后再套：被拦下的调用没有真正执行，不应占用工具配额。
 */
public final class ToolkitRequiredArgs {

    private ToolkitRequiredArgs() {
    }

    /**
     * 原地替换为 {@link RequiredArgGuardTool}。证据为空或工具表为空时仍会套，
     * 因为「无记忆无城市 → 追问」正是要靠空证据把臆造值拦下。
     *
     * @param toolkit     已装配并已套过配额的工具集
     * @param evidence    本轮可用的事实来源
     * @param rules       实体参数判据
     * @param canonicalOf 参数名/事实键 → 规范事实键
     * @return 同一 toolkit
     */
    public static Toolkit wrap(Toolkit toolkit, RequiredToolArgs.Evidence evidence,
                               RequiredToolArgs.Rules rules, Function<String, Optional<String>> canonicalOf) {
        return wrap(toolkit, evidence, rules, canonicalOf, new RequiredArgBlocks(), true);
    }

    /**
     * @param blocks  本轮拦截计数，用于升级追问措辞
     * @param enabled false 时原样返回，供配置一键关闭（默认开启）
     */
    public static Toolkit wrap(Toolkit toolkit, RequiredToolArgs.Evidence evidence,
                               RequiredToolArgs.Rules rules, Function<String, Optional<String>> canonicalOf,
                               RequiredArgBlocks blocks, boolean enabled) {
        if (!enabled || toolkit == null) {
            return toolkit;
        }
        // 先收集名字再替换，避免遍历期间改 registry
        Set<String> names = new HashSet<>(toolkit.getToolNames());
        for (String name : names) {
            AgentTool original = toolkit.getTool(name);
            if (original == null || original instanceof RequiredArgGuardTool) {
                continue;
            }
            toolkit.removeTool(name);
            toolkit.registerAgentTool(new RequiredArgGuardTool(original, evidence, rules, canonicalOf, blocks));
        }
        return toolkit;
    }
}

package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;

/**
 * 编排用的人话标签与小工具。所属域：runtime。
 *
 * <p>只做字符串归一，不查库、不读配置；模板执行器与汇总器共用，避免两处各写一份
 * 「名称 → 编码 → 兜底文案」的兜底链。
 */
public final class RouterTexts {

    private RouterTexts() {
    }

    /**
     * 子 Agent 显示名：名称 → 编码 → 兜底文案。
     *
     * @param agent 子 Agent；可空
     * @return 非空展示名
     */
    public static String childLabel(AgentDef agent) {
        if (agent == null) {
            return "子 Agent";
        }
        if (agent.getName() != null && !agent.getName().isBlank()) {
            return agent.getName().trim();
        }
        if (agent.getCode() != null && !agent.getCode().isBlank()) {
            return agent.getCode().trim();
        }
        return "子 Agent";
    }

    /**
     * 路由展示摘要。无 route_key 时不拼空括号；子 Agent 优先名称，否则编码。
     *
     * @param hit    命中的候选
     * @param reason 判定依据
     * @return 「路由到 …，依据：…」中文摘要
     */
    public static String routeSummary(Candidate hit, String reason) {
        String label = childLabel(hit.agent());
        String key = hit.route().getRouteKey() == null ? "" : hit.route().getRouteKey().trim();
        if (key.isEmpty()) {
            return "路由到 " + label + "，依据：" + reason;
        }
        return "路由到 " + label + "（" + key + "），依据：" + reason;
    }

    /**
     * 子分段标题：优先展示路由意图描述，缺失时退回子 Agent 名。
     *
     * @param outcome 子结果
     * @return 非空标题
     */
    public static String sectionTitle(ChildOutcome outcome) {
        Candidate hit = outcome.intent().candidate();
        String intent = hit.route().getIntent();
        if (intent != null && !intent.isBlank()) {
            return intent.trim();
        }
        return childLabel(hit.agent());
    }
}

package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.runtime.domain.model.TrialTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多意图决策块的人话版。所属域：runtime 的应用层单测。
 *
 * <p>本刀硬约束：ROUTE 事件正文里不得出现 {@code route_key} 那一套机器 JSON——
 * 它只用于分流与可观测（ROUTE span 的 {@code route_decision}），一旦进正文就会被
 * 方案块 / 气泡当成「方案自己的回答」展示给用户。
 */
class RouterTextsDecisionLineTest {

    @Test
    void decisionLineListsNamesWithoutMachineKeys() {
        List<RoutedIntent> intents = List.of(intent("制度知识专家", "k1"), intent("报告撰写专家", "k2"));

        String line = RouterTexts.decisionLine(intents);

        assertTrue(line.startsWith("路由决策"), line);
        assertTrue(line.contains("已判定 2 个意图"), line);
        assertTrue(line.contains("「制度知识专家」"), line);
        assertTrue(line.contains("「报告撰写专家」"), line);
        // 三个机器字段一个都不许出现
        assertFalse(line.contains("route_key"), line);
        assertFalse(line.contains("sub_query"), line);
        assertFalse(line.contains("k1"), line);
        assertFalse(line.contains("{"), line);
    }

    @Test
    void decisionLineDedupesRepeatedExpert() {
        List<RoutedIntent> intents = List.of(intent("制度知识专家", "k1"), intent("制度知识专家", "k2"));

        String line = RouterTexts.decisionLine(intents);

        // 条数是真实的判定条数，名单去重，避免同名专家出现两次
        assertTrue(line.contains("已判定 2 个意图"), line);
        assertTrue(line.indexOf("「制度知识专家」") == line.lastIndexOf("「制度知识专家」"), line);
    }

    @Test
    void concatHeadingUsesAgentNameNotIntent() {
        AgentDef agent = new AgentDef();
        agent.setName("文稿起草");
        agent.setCode("draft");
        AgentRoute route = new AgentRoute();
        route.setRouteKey("draft");
        route.setIntent("写一篇纪要");
        RoutedIntent intent = new RoutedIntent(new Candidate(route, agent), "子问题", "依据");

        ChildOutcome outcome = ChildOutcome.ok(intent, List.of(), new TrialTurn());

        assertEquals("文稿起草", RouterTexts.concatHeading(outcome));
        assertEquals("写一篇纪要", RouterTexts.sectionTitle(outcome));
    }

    @Test
    void emptyIntentsProduceEmptyLine() {
        assertTrue(RouterTexts.decisionLine(List.of()).isEmpty());
        assertTrue(RouterTexts.decisionLine(null).isEmpty());
    }

    private static RoutedIntent intent(String agentName, String routeKey) {
        AgentDef agent = new AgentDef();
        agent.setName(agentName);
        agent.setCode(routeKey);
        AgentRoute route = new AgentRoute();
        route.setRouteKey(routeKey);
        return new RoutedIntent(new Candidate(route, agent), "子问题", "依据");
    }
}

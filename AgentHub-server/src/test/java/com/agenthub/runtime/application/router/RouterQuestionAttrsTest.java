package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多问拆分的只读打点与去重名单。所属域：runtime 的应用层单测。
 *
 * <p>硬约束：ROUTE 步骤只落「是否拆分 / 问题条数 / 去重目标数」三个计数，
 * <b>不落</b>问题正文与目标名（用户问句可能含业务数据，attrs 会进追踪表）；
 * 决策块正文里的名单与计数的去重口径必须是同一份（{@link RouterTexts#distinctLabels}）。
 */
class RouterQuestionAttrsTest {

    @Test
    void questionAttrsWritesCountsOnly() {
        Map<String, Object> fields = new LinkedHashMap<>();

        RouterObs.questionAttrs(fields, true, 2, 2);

        assertEquals(Boolean.TRUE, fields.get("ma.question_split"));
        assertEquals(2, fields.get("ma.question_count"));
        assertEquals(2, fields.get("ma.question_topic_count"));
        // 只有这三个键：不写正文、不写目标名
        assertEquals(3, fields.size());
    }

    @Test
    void questionAttrsKeepsNonNegativeCountsAndNullSafe() {
        Map<String, Object> fields = new LinkedHashMap<>();
        RouterObs.questionAttrs(fields, false, 0, 0);
        assertEquals(Boolean.FALSE, fields.get("ma.question_split"));
        assertEquals(0, fields.get("ma.question_count"));
        assertEquals(0, fields.get("ma.question_topic_count"));

        // 空 map 入参不抛：打点是旁路，不能因为少给一个 map 把主链打断
        RouterObs.questionAttrs(null, true, 1, 1);
    }

    @Test
    void distinctLabelsPreservesOrderAndDedupes() {
        List<RoutedIntent> intents = List.of(
                intent("企业综合台", "enterprise"),
                intent("制度要点与报告", "wf_report"),
                intent("企业综合台", "enterprise2"));

        List<String> labels = RouterTexts.distinctLabels(intents);

        assertEquals(List.of("企业综合台", "制度要点与报告"), labels);
        // 计数用的是这一份名单，条数仍按真实意图数
        assertEquals(2, labels.size());
        assertTrue(RouterTexts.distinctLabels(List.of()).isEmpty());
        assertTrue(RouterTexts.distinctLabels(null).isEmpty());
    }

    @Test
    void questionDecisionLineUsesSameDedupedLabels() {
        List<RoutedIntent> intents = List.of(
                intent("企业综合台", "enterprise"),
                intent("文稿起草", "draft"));

        String line = RouterTexts.questionDecisionLine(intents);

        assertTrue(line.contains("已拆成 2 个问题"), line);
        assertTrue(line.contains("「企业综合台」"), line);
        assertTrue(line.contains("「文稿起草」"), line);
        // 人话版不带机器键
        assertFalse(line.contains("route_key"), line);
        assertFalse(line.contains("sub_query"), line);
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

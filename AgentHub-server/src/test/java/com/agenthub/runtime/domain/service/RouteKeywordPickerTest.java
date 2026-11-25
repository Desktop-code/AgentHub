package com.agenthub.runtime.domain.service;

import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键字路由只看启用行，并保持传入顺序。
 */
class RouteKeywordPickerTest {

    /**
     * intent 命中第二条；停用的更早行不能抢先。
     */
    @Test
    void matchesEnabledIntentInOrder() {
        AgentRoute disabled = route(1L, "billing", "账单", AgentStatus.DISABLED);
        AgentRoute later = route(2L, "order", "订单查询", AgentStatus.ENABLED);
        Optional<AgentRoute> hit = RouteKeywordPicker.match("帮我做订单查询", List.of(disabled, later));
        assertTrue(hit.isPresent());
        assertEquals(2L, hit.get().getChildAgentId());
    }

    /**
     * 没有关键字时不猜测第一条，交给上层决定。
     */
    @Test
    void emptyWhenNothingMatches() {
        AgentRoute route = route(3L, "hr", "人事", AgentStatus.ENABLED);
        assertTrue(RouteKeywordPicker.match("今天天气", List.of(route)).isEmpty());
    }

    /**
     * 合问可命中多个；保持 sort 顺序并截顶。
     */
    @Test
    void matchAllKeepsOrderAndCap() {
        AgentRoute weather = route(1L, "weather", "天气", AgentStatus.ENABLED);
        AgentRoute user = route(2L, "user", "用户", AgentStatus.ENABLED);
        AgentRoute order = route(3L, "order", "订单", AgentStatus.ENABLED);
        List<AgentRoute> hits = RouteKeywordPicker.matchAll("查用户和天气", List.of(weather, user, order), 2);
        assertEquals(2, hits.size());
        assertEquals(1L, hits.get(0).getChildAgentId());
        assertEquals(2L, hits.get(1).getChildAgentId());
    }

    private AgentRoute route(Long childId, String key, String intent, int status) {
        AgentRoute route = new AgentRoute();
        route.setChildAgentId(childId);
        route.setRouteKey(key);
        route.setIntent(intent);
        route.setStatus(status);
        return route;
    }
}

package com.agenthub.agent.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * route_key / sort 默认生成与 code 规范化规则。
 */
class AgentRouteDefaultsTest {

    @Test
    void normalizeRemovesSpacesAndIllegalChars() {
        assertEquals("MCPTestAgent", AgentRouteDefaults.normalizeCode("MCP Test Agent"));
        assertEquals("MCPTestAgent", AgentRouteDefaults.normalizeCode("  MCP Test Agent  "));
        assertEquals("bad_code", AgentRouteDefaults.normalizeCode("bad-code"));
        // 连续非法只压一个 _；原有合法 _ 保留
        assertEquals("a_b__c", AgentRouteDefaults.normalizeCode("a--b__c"));
        assertEquals("weather_agent", AgentRouteDefaults.normalizeCode("weather_agent"));
        assertNull(AgentRouteDefaults.normalizeCode("   "));
        assertNull(AgentRouteDefaults.normalizeCode("---"));
        assertNull(AgentRouteDefaults.normalizeCode(null));
    }

    @Test
    void prefersNormalizedCodeWhenFree() {
        assertTrue(AgentRouteDefaults.isValidKeyCandidate("MCP Test Agent"));
        assertEquals("MCPTestAgent",
                AgentRouteDefaults.resolveKey("MCP Test Agent", 18L, null, Set.of("other")));
        assertEquals("weather_agent",
                AgentRouteDefaults.resolveKey("weather_agent", 9L, null, Set.of("other")));
    }

    @Test
    void fallsBackWhenNormalizedTakenOrEmpty() {
        assertEquals("child_18",
                AgentRouteDefaults.resolveKey("MCP Test Agent", 18L, null, Set.of("MCPTestAgent")));
        assertEquals("child_9",
                AgentRouteDefaults.resolveKey("---", 9L, null, List.of()));
        assertEquals("child_9_3",
                AgentRouteDefaults.resolveKey(null, 9L, 3L, Set.of("child_9")));
    }

    @Test
    void systemFallbackKeyDetectsChildPrefixOnly() {
        assertTrue(AgentRouteDefaults.isSystemFallbackKey(null, 18L, 3L));
        assertTrue(AgentRouteDefaults.isSystemFallbackKey("", 18L, 3L));
        assertTrue(AgentRouteDefaults.isSystemFallbackKey("child_18", 18L, 3L));
        assertTrue(AgentRouteDefaults.isSystemFallbackKey("child_18_3", 18L, 3L));
        // 已手改的高级 key 禁止覆盖
        assertFalse(AgentRouteDefaults.isSystemFallbackKey("QAAgent", 18L, 3L));
        assertFalse(AgentRouteDefaults.isSystemFallbackKey("MCPTestAgent", 18L, 3L));
        assertFalse(AgentRouteDefaults.isSystemFallbackKey("child_99", 18L, 3L));
    }

    @Test
    void nextSortStartsAtTenThenStepsByTen() {
        assertEquals(10, AgentRouteDefaults.nextSort(null));
        assertEquals(20, AgentRouteDefaults.nextSort(10));
        assertEquals(40, AgentRouteDefaults.nextSort(30));
    }
}

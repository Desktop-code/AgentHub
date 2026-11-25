package com.agenthub.runtime.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.common.config.RuntimeLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空覆盖吃 YAML 默认；有覆盖时再与硬顶取 min。
 */
class RuntimeLimitResolverTest {

    private RuntimeLimitResolver resolver;
    private RuntimeLimitsProperties props;

    @BeforeEach
    void setUp() {
        props = new RuntimeLimitsProperties();
        resolver = new RuntimeLimitResolver(props);
    }

    @Test
    void nullAgentUsesDefaults() {
        EffectiveLimits limits = resolver.resolve(null);
        assertEquals(props.getTimeoutMs().getDefault(), limits.timeoutMs());
        assertEquals(props.getMaxRounds().getDefault(), limits.maxRounds());
        assertEquals(props.getToolResultMaxChars().getDefault(), limits.toolResultMaxChars());
    }

    @Test
    void agentOverrideIsCappedByMax() {
        AgentDef agent = new AgentDef();
        agent.setMaxRounds((int) props.getMaxRounds().getMax() + 10);
        agent.setTimeoutMs(props.getTimeoutMs().getDefault());
        EffectiveLimits limits = resolver.resolve(agent);
        assertEquals(props.getMaxRounds().getMax(), limits.maxRounds());
        assertEquals(props.getTimeoutMs().getDefault(), limits.timeoutMs());
    }

    @Test
    void smallerOverrideWins() {
        AgentDef agent = new AgentDef();
        agent.setMaxRounds(2);
        agent.setTimeoutMs(5_000L);
        EffectiveLimits limits = resolver.resolve(agent);
        assertEquals(2, limits.maxRounds());
        assertEquals(5_000L, limits.timeoutMs());
        assertTrue(limits.timeoutMs() <= props.getTimeoutMs().getMax());
    }
}

package com.agenthub.agent.application;

import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.PurposeResolveResult;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * MEMORY 与 SUMMARY 独立解析；MEMORY 可降 CHAT 不借 SUMMARY；CHAT 槽内按 priority 切备。
 */
@ExtendWith(MockitoExtension.class)
class PurposeModelResolverTest {

    @Mock
    private AgentModelBindRepository binds;
    @Mock
    private ModelInstanceRepository instances;

    private PurposeModelResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PurposeModelResolver(binds, instances);
    }

    @Test
    void memoryDoesNotBorrowSummary() {
        when(binds.listByAgentId(1L)).thenReturn(List.of(bind(10L, 100L, "SUMMARY", 10)));
        when(instances.findById(100L)).thenReturn(Optional.of(chatInstance(100L, "SUMMARY")));
        when(instances.listEnabledByCapability("MEMORY")).thenReturn(List.of());

        assertTrue(resolver.resolve(1L, ModelCapability.MEMORY, null, null, false).isEmpty());
        assertTrue(resolver.resolve(1L, ModelCapability.SUMMARY, null, null, false).isPresent());
    }

    @Test
    void memoryDegradesToChatNotSummary() {
        when(binds.listByAgentId(1L)).thenReturn(List.of(
                bind(11L, 200L, "CHAT", 10),
                bind(12L, 201L, "SUMMARY", 10)));
        when(instances.findById(200L)).thenReturn(Optional.of(chatInstance(200L, "CHAT")));
        when(instances.findById(201L)).thenReturn(Optional.of(chatInstance(201L, "SUMMARY")));
        when(instances.listEnabledByCapability("MEMORY")).thenReturn(List.of());

        PurposeResolveResult hit = resolver.resolve(1L, ModelCapability.MEMORY, null, null, true).orElseThrow();
        assertEquals(200L, hit.getInstanceId());
        assertEquals("DEGRADED_CHAT", hit.getSource());
        assertEquals("CHAT", hit.getDegradedPurpose());
        assertFalse("SUMMARY".equals(hit.getDegradedPurpose()));
    }

    @Test
    void chatSlotFailsOverByPriority() {
        when(binds.listByAgentId(1L)).thenReturn(List.of(
                bind(21L, 301L, "CHAT", 20),
                bind(20L, 300L, "CHAT", 10)));
        ModelInstance dead = chatInstance(300L, "CHAT");
        dead.setStatus(0);
        when(instances.findById(300L)).thenReturn(Optional.of(dead));
        when(instances.findById(301L)).thenReturn(Optional.of(chatInstance(301L, "CHAT")));

        PurposeResolveResult hit = resolver.resolve(1L, ModelCapability.CHAT, null, null, false).orElseThrow();
        assertEquals(301L, hit.getInstanceId());
        assertEquals("SLOT", hit.getSource());
        assertEquals(2, hit.getFailoverPriority());
    }

    @Test
    void summaryIndependentFromMemoryBind() {
        when(binds.listByAgentId(1L)).thenReturn(List.of(bind(30L, 400L, "MEMORY", 10)));
        when(instances.findById(400L)).thenReturn(Optional.of(chatInstance(400L, "MEMORY")));
        when(instances.listEnabledByCapability("SUMMARY")).thenReturn(List.of());

        assertTrue(resolver.resolve(1L, ModelCapability.SUMMARY, null, null, false).isEmpty());
        assertTrue(resolver.resolve(1L, ModelCapability.MEMORY, null, null, false).isPresent());
    }

    private static AgentModelBind bind(long id, long instanceId, String purpose, int priority) {
        AgentModelBind bind = new AgentModelBind();
        bind.setId(id);
        bind.setAgentId(1L);
        bind.setModelInstanceId(instanceId);
        bind.setPurpose(purpose);
        bind.setPriority(priority);
        bind.setStatus(AgentStatus.ENABLED);
        return bind;
    }

    private static ModelInstance chatInstance(long id, String capability) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setCapability(capability);
        instance.setStatus(1);
        instance.setDisplayName("m" + id);
        return instance;
    }
}

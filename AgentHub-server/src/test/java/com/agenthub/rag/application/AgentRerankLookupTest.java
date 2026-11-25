package com.agenthub.rag.application;

import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 读 Agent 侧重排绑定的口径。所属域：rag 的 application。
 * <p>
 * 覆盖点：无绑定返回空；停用的绑定/停用的实例都不算生效；
 * 非 RERANK 用途不误判；多条时取第一条启用且可用的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRerankLookupTest {

    @Mock
    private AgentModelBindRepository binds;
    @Mock
    private ModelInstanceRepository instances;

    private AgentRerankLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new AgentRerankLookup(binds, instances);
        lenient().when(binds.listByAgentId(anyLong())).thenReturn(List.of());
    }

    /**
     * 没有绑定：视为未配置，交给知识库自带配置兜底。
     */
    @Test
    void emptyWhenNoBind() {
        assertTrue(lookup.enabledRerankModelId(7L).isEmpty());
    }

    /**
     * 正常一条：绑定与实例都启用且用途 RERANK 时取出实例 id。
     */
    @Test
    void returnsEnabledRerankInstance() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(900L, 77L, 1, "RERANK")));
        when(instances.findById(77L)).thenReturn(Optional.of(instance(77L, "RERANK", 1)));

        assertEquals(Optional.of(77L), lookup.enabledRerankModelId(7L));
    }

    /**
     * 绑定停用：不算生效，避免「界面上关掉了但运行时还在重排」。
     */
    @Test
    void disabledBindIgnored() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(900L, 77L, 0, "RERANK")));
        when(instances.findById(77L)).thenReturn(Optional.of(instance(77L, "RERANK", 1)));

        assertTrue(lookup.enabledRerankModelId(7L).isEmpty());
    }

    /**
     * 实例停用：同样不算生效。
     */
    @Test
    void disabledInstanceIgnored() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(900L, 77L, 1, "RERANK")));
        when(instances.findById(77L)).thenReturn(Optional.of(instance(77L, "RERANK", 0)));

        assertTrue(lookup.enabledRerankModelId(7L).isEmpty());
    }

    /**
     * 非 RERANK 用途：不能被当成重排配置（防止 CHAT 行误触发精排出站）。
     */
    @Test
    void nonRerankPurposeIgnored() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(900L, 77L, 1, "CHAT")));
        when(instances.findById(77L)).thenReturn(Optional.of(instance(77L, "CHAT", 1)));

        assertTrue(lookup.enabledRerankModelId(7L).isEmpty());
    }

    /**
     * 多条时跳过不可用行，取第一条有效的。
     */
    @Test
    void picksFirstUsableRow() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(
                bind(900L, 77L, 1, "RERANK"),
                bind(901L, 78L, 1, "RERANK")));
        // 77 的实例已删，只有 78 可用
        when(instances.findById(77L)).thenReturn(Optional.empty());
        when(instances.findById(78L)).thenReturn(Optional.of(instance(78L, "RERANK", 1)));

        assertEquals(Optional.of(78L), lookup.enabledRerankModelId(7L));
    }

    private AgentModelBind bind(Long id, Long instanceId, int status, String purpose) {
        AgentModelBind bind = new AgentModelBind();
        bind.setId(id);
        bind.setAgentId(7L);
        bind.setModelInstanceId(instanceId);
        bind.setPurpose(purpose);
        bind.setPriority(10);
        bind.setStatus(status);
        return bind;
    }

    private ModelInstance instance(Long id, String capability, int status) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setDisplayName("rerank-" + id);
        instance.setCapability(capability);
        instance.setStatus(status);
        return instance;
    }
}

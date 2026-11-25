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
 * 读 Agent 的 REWRITE 槽。所属域：rag 的 application。
 * <p>
 * 覆盖点：无绑定返回空；停用的绑定 / 停用的实例都不算生效；
 * 非 REWRITE 用途（尤其 CHAT）不能被借用；多条时取第一条有效行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRewriteLookupTest {

    @Mock
    private AgentModelBindRepository binds;
    @Mock
    private ModelInstanceRepository instances;

    private AgentRewriteLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new AgentRewriteLookup(binds, instances);
        lenient().when(binds.listByAgentId(anyLong())).thenReturn(List.of());
    }

    /**
     * 没有绑定：视为不具备改写能力，交给检索侧按原问句走。
     */
    @Test
    void emptyWhenNoBind() {
        assertTrue(lookup.enabledRewriteBind(7L).isEmpty());
    }

    /**
     * 正常一条：绑定与实例都启用且用途 REWRITE 时取出该绑定（含 L3 四列）。
     */
    @Test
    void returnsEnabledRewriteBindWithL3() {
        AgentModelBind bind = bind(900L, 88L, 1, "REWRITE");
        bind.setPromptExtra("优先保留条款编号");
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind));
        when(instances.findById(88L)).thenReturn(Optional.of(instance(88L, "REWRITE", 1)));

        Optional<AgentModelBind> hit = lookup.enabledRewriteBind(7L);

        assertTrue(hit.isPresent());
        assertEquals(88L, hit.get().getModelInstanceId());
        assertEquals("优先保留条款编号", hit.get().getPromptExtra());
    }

    /**
     * 绑定停用：不算生效，避免「界面关掉了但运行时还在改写」。
     */
    @Test
    void disabledBindIgnored() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(900L, 88L, 0, "REWRITE")));
        when(instances.findById(88L)).thenReturn(Optional.of(instance(88L, "REWRITE", 1)));

        assertTrue(lookup.enabledRewriteBind(7L).isEmpty());
    }

    /**
     * 实例停用：同样不算生效。
     */
    @Test
    void disabledInstanceIgnored() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(900L, 88L, 1, "REWRITE")));
        when(instances.findById(88L)).thenReturn(Optional.of(instance(88L, "REWRITE", 0)));

        assertTrue(lookup.enabledRewriteBind(7L).isEmpty());
    }

    /**
     * CHAT 用途行不能被当成改写模型：这是本刀明令禁止的降级。
     */
    @Test
    void chatPurposeIgnored() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(900L, 88L, 1, "CHAT")));
        when(instances.findById(88L)).thenReturn(Optional.of(instance(88L, "CHAT", 1)));

        assertTrue(lookup.enabledRewriteBind(7L).isEmpty());
    }

    /**
     * 多条时跳过不可用行，取第一条有效行。
     */
    @Test
    void picksFirstUsableRow() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(
                bind(900L, 88L, 1, "REWRITE"),
                bind(901L, 89L, 1, "REWRITE")));
        // 88 的实例已删，只有 89 可用
        when(instances.findById(88L)).thenReturn(Optional.empty());
        when(instances.findById(89L)).thenReturn(Optional.of(instance(89L, "REWRITE", 1)));

        assertEquals(89L, lookup.enabledRewriteBind(7L).orElseThrow().getModelInstanceId());
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
        instance.setDisplayName("rewrite-" + id);
        instance.setCapability(capability);
        instance.setStatus(status);
        return instance;
    }
}

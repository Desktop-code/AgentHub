package com.agenthub.rag.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.rag.api.dto.AgentRerankResp;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 检索链路重排配置。所属域：rag 的 application。
 * <p>
 * 覆盖点：
 * <ol>
 *   <li>未配置时读接口返回空对象，不是 null；</li>
 *   <li>设置重排会写入 agent_model_bind(purpose=RERANK)，实例必须是 RERANK 用途；</li>
 *   <li>换模型先删旧行再插新行（绑定表不支持改实例）；</li>
 *   <li>传 0 清空，重复清空不报错（幂等）；</li>
 *   <li>同一实例已绑在其它槽时拒绝，避免撞 (agent, instance) 唯一约束。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRetrievalConfigAppServiceTest {

    @Mock
    private AgentDefRepository agents;
    @Mock
    private AgentModelBindRepository binds;
    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private RagModelLookup models;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;

    private AgentRetrievalConfigAppService service;

    @BeforeEach
    void setUp() {
        service = new AgentRetrievalConfigAppService(agents, binds, instances, models,
                operatorContext, operLogWriter);
        AgentDef agent = new AgentDef();
        agent.setId(7L);
        lenient().when(agents.findById(7L)).thenReturn(Optional.of(agent));
        lenient().when(operatorContext.requireUserId()).thenReturn(9L);
        lenient().when(binds.listByAgentId(7L)).thenReturn(List.of());
    }

    /**
     * 未配置：读接口给空对象，前端可直接绑定而不必判 null。
     */
    @Test
    void readReturnsEmptyWhenNotConfigured() {
        AgentRerankResp resp = service.read(7L);

        assertNull(resp.getBindId());
        assertNull(resp.getModelInstanceId());
    }

    /**
     * 首次设置：插入一条 RERANK 绑定，优先级固定 10。
     */
    @Test
    void saveInsertsRerankBind() {
        when(models.requireRerank(77L)).thenReturn(instance(77L, "gte-rerank"));
        when(instances.findById(77L)).thenReturn(Optional.of(instance(77L, "gte-rerank")));
        // 调用顺序：查已有重排行（空）→ 去重遍历（空）→ 落库后回读（新行）
        when(binds.listByAgentId(7L)).thenReturn(List.of(), List.of(), List.of(rerankBind(900L, 77L)));

        AgentRerankResp resp = service.save(7L, 77L);

        verify(binds).insert(any(), eq(9L));
        assertEquals(900L, resp.getBindId());
        assertEquals(77L, resp.getModelInstanceId());
    }

    /**
     * 换模型：旧行逻辑删 + 插新行，不能只改 priority 就当成换了模型。
     */
    @Test
    void saveReplacesExistingRerankBind() {
        AgentModelBind old = rerankBind(900L, 77L);
        ModelInstance target = instance(78L, "bge-reranker");
        when(instances.findById(77L)).thenReturn(Optional.of(instance(77L, "gte-rerank")));
        when(instances.findById(78L)).thenReturn(Optional.of(target));
        when(models.requireRerank(78L)).thenReturn(target);
        when(binds.listByAgentId(7L))
                .thenReturn(List.of(old), List.of(old), List.of(rerankBind(901L, 78L)));

        service.save(7L, 78L);

        verify(binds).logicalDelete(900L, 9L);
        verify(binds).insert(any(), eq(9L));
    }

    /**
     * 传 0 清空：逻辑删那条并返回空；没有配置时重复清空不报错。
     */
    @Test
    void saveZeroClearsRerank() {
        AgentModelBind existing = rerankBind(900L, 77L);
        when(instances.findById(77L)).thenReturn(Optional.of(instance(77L, "gte-rerank")));
        when(binds.listByAgentId(7L)).thenReturn(List.of(existing), List.of());

        AgentRerankResp cleared = service.save(7L, 0L);

        verify(binds).logicalDelete(900L, 9L);
        assertNull(cleared.getModelInstanceId());

        // 已没有绑定：再清一次不抛错、不重复删
        assertNull(service.save(7L, 0L).getModelInstanceId());
        verify(binds).logicalDelete(anyLong(), anyLong());
    }

    /**
     * 同一实例已绑在其它用途：拒绝，避免撞 (agent, instance) 唯一。
     */
    @Test
    void saveRejectsInstanceBoundToOtherSlot() {
        when(models.requireRerank(77L)).thenReturn(instance(77L, "gte-rerank"));
        AgentModelBind chatBind = new AgentModelBind();
        chatBind.setId(901L);
        chatBind.setAgentId(7L);
        chatBind.setModelInstanceId(77L);
        chatBind.setPurpose("CHAT");
        when(binds.listByAgentId(7L)).thenReturn(List.of(chatBind));

        assertThrows(BizException.class, () -> service.save(7L, 77L));
        verify(binds, never()).insert(any(), anyLong());
    }

    /**
     * 非 RERANK 实例被拒：用途校验交给 RagModelLookup，这里断言不写库。
     */
    @Test
    void saveRejectsWrongCapability() {
        when(models.requireRerank(55L)).thenThrow(new BizException(
                com.agenthub.common.error.ErrorCode.BAD_REQUEST, "所选模型用途不是 RERANK"));

        BizException ex = assertThrows(BizException.class, () -> service.save(7L, 55L));
        assertTrue(ex.getMessage().contains("RERANK"));
        verify(binds, never()).insert(any(), anyLong());
    }

    private ModelInstance instance(Long id, String displayName) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setDisplayName(displayName);
        instance.setModelId(displayName);
        instance.setCapability("RERANK");
        instance.setStatus(1);
        return instance;
    }

    private AgentModelBind rerankBind(Long id, Long instanceId) {
        AgentModelBind bind = new AgentModelBind();
        bind.setId(id);
        bind.setAgentId(7L);
        bind.setModelInstanceId(instanceId);
        bind.setPurpose("RERANK");
        bind.setPriority(10);
        bind.setStatus(1);
        return bind;
    }
}

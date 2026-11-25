package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.ModelBindCreateReq;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型绑定保存校验。所属域：agent。
 * 覆盖：实例 capability 与槽用途不一致时拒绝写入。
 */
@ExtendWith(MockitoExtension.class)
class AgentModelBindAppServiceTest {

    @Mock
    private AgentLookup lookup;
    @Mock
    private AgentModelBindRepository binds;
    @Mock
    private AgentRefLookup refs;
    @Mock
    private PurposeModelResolver purposeResolver;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private com.agenthub.agent.domain.service.AgentPromptTemplatePort promptTemplates;

    private AgentModelBindAppService service;

    @BeforeEach
    void setUp() {
        service = new AgentModelBindAppService(lookup, binds, refs, purposeResolver, operatorContext, operLogWriter,
                promptTemplates);
    }

    /**
     * 槽用途与实例能力不一致：报错且不 insert。
     * 用 SUMMARY 槽 + EMBEDDING 实例：两者都在册（SUMMARY 可绑），错配拦截才有意义；
     * RERANK 槽本身的入口已在 {@link #createRejectsRerankBinding()} 单测里被挡下。
     */
    @Test
    void createRejectsCapabilityMismatch() {
        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setRunMode("REACT");
        when(lookup.require(1L)).thenReturn(agent);

        ModelInstance embed = new ModelInstance();
        embed.setId(88L);
        embed.setCapability("EMBEDDING");
        embed.setDisplayName("bge");
        embed.setStatus(1);
        when(refs.requireModel(88L, true)).thenReturn(embed);
        when(binds.existsPair(1L, 88L, null)).thenReturn(false);

        ModelBindCreateReq req = new ModelBindCreateReq();
        req.setModelInstanceId(88L);
        req.setPurpose("SUMMARY");

        BizException ex = assertThrows(BizException.class, () -> service.create(1L, req));
        assertTrue(ex.getMessage().contains("不一致"));
        assertTrue(ex.getMessage().contains("EMBEDDING"));
        assertTrue(ex.getMessage().contains("SUMMARY"));
        verify(binds, never()).insert(any(), anyLong());
    }

    /**
     * EMBEDDING 实例经槽入口新建：拒绝并提示去「知识库」，且不 insert。
     */
    @Test
    void createRejectsEmbeddingBinding() {
        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setRunMode("REACT");
        when(lookup.require(1L)).thenReturn(agent);

        ModelInstance embed = new ModelInstance();
        embed.setId(88L);
        embed.setCapability("EMBEDDING");
        embed.setDisplayName("bge");
        embed.setStatus(1);
        when(refs.requireModel(88L, true)).thenReturn(embed);
        when(binds.existsPair(1L, 88L, null)).thenReturn(false);

        ModelBindCreateReq req = new ModelBindCreateReq();
        req.setModelInstanceId(88L);

        BizException ex = assertThrows(BizException.class, () -> service.create(1L, req));
        assertTrue(ex.getMessage().contains("知识库"));
        verify(binds, never()).insert(any(), anyLong());
    }

    /**
     * RERANK 实例经槽入口新建：拒绝并指向「知识库」Tab 的检索链路，且不 insert。
     */
    @Test
    void createRejectsRerankBinding() {
        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setRunMode("REACT");
        when(lookup.require(1L)).thenReturn(agent);

        ModelInstance rerank = new ModelInstance();
        rerank.setId(77L);
        rerank.setCapability("RERANK");
        rerank.setDisplayName("gte-rerank");
        rerank.setStatus(1);
        when(refs.requireModel(77L, true)).thenReturn(rerank);
        when(binds.existsPair(1L, 77L, null)).thenReturn(false);

        ModelBindCreateReq req = new ModelBindCreateReq();
        req.setModelInstanceId(77L);

        BizException ex = assertThrows(BizException.class, () -> service.create(1L, req));
        assertTrue(ex.getMessage().contains("知识库"));
        verify(binds, never()).insert(any(), anyLong());
    }

    /**
     * SUMMARY / MEMORY 槽恢复可绑：写库成功（list 回读空导致 NSEE 证明已写入）。
     * 入口已迁到「记忆配置」Tab，但后端写路径不变，仍需保证这两个用途可 bind。
     */
    @Test
    void createAcceptsSummaryAndMemorySlots() {
        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setRunMode("REACT");
        when(lookup.require(1L)).thenReturn(agent);

        ModelInstance summary = new ModelInstance();
        summary.setId(31L);
        summary.setCapability("SUMMARY");
        summary.setDisplayName("sum");
        summary.setStatus(1);
        when(refs.requireModel(31L, true)).thenReturn(summary);
        when(binds.existsPair(1L, 31L, null)).thenReturn(false);
        when(purposeResolver.listSlotBinds(anyLong(), any())).thenReturn(java.util.List.of());
        when(operatorContext.requireUserId()).thenReturn(3L);
        when(binds.insert(any(), anyLong())).thenReturn(101L);
        when(binds.listByAgentId(1L)).thenReturn(java.util.List.of());

        ModelBindCreateReq req = new ModelBindCreateReq();
        req.setModelInstanceId(31L);
        req.setPurpose("SUMMARY");

        assertThrows(java.util.NoSuchElementException.class, () -> service.create(1L, req));
        verify(binds).insert(any(), anyLong());
    }

    /**
     * MEMORY 槽同一写路径：记忆页绑「抽取模型」写的就是这张表，不能被错槽校验拦下。
     */
    @Test
    void createAcceptsMemorySlot() {
        AgentDef agent = new AgentDef();
        agent.setId(2L);
        agent.setRunMode("REACT");
        when(lookup.require(2L)).thenReturn(agent);

        ModelInstance memory = new ModelInstance();
        memory.setId(41L);
        memory.setCapability("MEMORY");
        memory.setDisplayName("extract");
        memory.setStatus(1);
        when(refs.requireModel(41L, true)).thenReturn(memory);
        when(binds.existsPair(2L, 41L, null)).thenReturn(false);
        when(purposeResolver.listSlotBinds(anyLong(), any())).thenReturn(java.util.List.of());
        when(operatorContext.requireUserId()).thenReturn(3L);
        when(binds.insert(any(), anyLong())).thenReturn(102L);
        when(binds.listByAgentId(2L)).thenReturn(java.util.List.of());

        ModelBindCreateReq req = new ModelBindCreateReq();
        req.setModelInstanceId(41L);
        req.setPurpose("MEMORY");

        assertThrows(java.util.NoSuchElementException.class, () -> service.create(2L, req));
        verify(binds).insert(any(), anyLong());
    }

    /**
     * CHAT 槽绑 Chat 实例：通过用途校验并 insert（list 回读空导致 NSEE，证明已写入）。
     */
    @Test
    void createAcceptsMatchingCapability() {        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setRunMode("REACT");
        when(lookup.require(1L)).thenReturn(agent);

        ModelInstance chat = new ModelInstance();
        chat.setId(9L);
        chat.setCapability("CHAT");
        chat.setDisplayName("qwen");
        chat.setStatus(1);
        when(refs.requireModel(9L, true)).thenReturn(chat);
        when(binds.existsPair(1L, 9L, null)).thenReturn(false);
        when(purposeResolver.listSlotBinds(anyLong(), any())).thenReturn(java.util.List.of());
        when(operatorContext.requireUserId()).thenReturn(3L);
        when(binds.insert(any(), anyLong())).thenReturn(100L);
        when(binds.listByAgentId(1L)).thenReturn(java.util.List.of());

        ModelBindCreateReq req = new ModelBindCreateReq();
        req.setModelInstanceId(9L);
        req.setPurpose("CHAT");

        assertThrows(java.util.NoSuchElementException.class, () -> service.create(1L, req));
        verify(binds).insert(any(), anyLong());
    }

    /**
     * 首次为某用途绑定模型槽：该用途已有已发布生效 L2 时自动钉住默认模板（本刀验收点）。
     * <p>
     * 用 SUMMARY 槽验证「非 CHAT 也默认绑」；断言落在写入的绑定行上，
     * 因为用例末尾的 list 回读空导致 NSEE，和既有写路径用例保持同一写法。
     */
    @Test
    void createAutoBindsDefaultTemplateForPurposeSlot() {
        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setRunMode("REACT");
        when(lookup.require(1L)).thenReturn(agent);

        ModelInstance summary = new ModelInstance();
        summary.setId(31L);
        summary.setCapability("SUMMARY");
        summary.setDisplayName("sum");
        summary.setStatus(1);
        when(refs.requireModel(31L, true)).thenReturn(summary);
        when(binds.existsPair(1L, 31L, null)).thenReturn(false);
        when(purposeResolver.listSlotBinds(anyLong(), any())).thenReturn(java.util.List.of());
        when(operatorContext.requireUserId()).thenReturn(3L);
        when(binds.insert(any(), anyLong())).thenReturn(101L);
        when(binds.listByAgentId(1L)).thenReturn(java.util.List.of());

        // 该用途有生效版本：新建时必须自动钉住，而不是留空等运行时失败
        com.agenthub.agent.domain.model.AgentPromptTemplateOption def =
                new com.agenthub.agent.domain.model.AgentPromptTemplateOption();
        def.setId(4L);
        def.setTemplateKey("SUMMARY");
        def.setName("会话摘要模板");
        def.setPurpose("SUMMARY");
        def.setLocale("zh-CN");
        def.setVersion(1);
        def.setIsActive(1);
        when(promptTemplates.supportsPurpose("SUMMARY")).thenReturn(true);
        when(promptTemplates.defaultOption("SUMMARY", "zh-CN")).thenReturn(def);
        when(promptTemplates.normalize("SUMMARY", 4L, 1, null, null))
                .thenReturn(new com.agenthub.agent.domain.model.AgentPromptBinding(4L, 1, null, null));

        ModelBindCreateReq req = new ModelBindCreateReq();
        req.setModelInstanceId(31L);
        req.setPurpose("SUMMARY");

        org.mockito.ArgumentCaptor<com.agenthub.agent.domain.model.AgentModelBind> captor =
                org.mockito.ArgumentCaptor.forClass(com.agenthub.agent.domain.model.AgentModelBind.class);
        assertThrows(java.util.NoSuchElementException.class, () -> service.create(1L, req));
        verify(binds).insert(captor.capture(), anyLong());
        org.junit.jupiter.api.Assertions.assertEquals(4L, captor.getValue().getPromptTemplateId());
        org.junit.jupiter.api.Assertions.assertEquals(1, captor.getValue().getTemplateVersion());
    }
}

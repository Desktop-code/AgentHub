package com.agenthub.rag.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.rag.api.dto.KbBindCreateReq;
import com.agenthub.rag.api.dto.KbBindResp;
import com.agenthub.rag.domain.model.AgentKbBind;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.repository.AgentKbBindRepository;
import com.agenthub.rag.domain.repository.RagKnowledgeBaseRepository;
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
 * Agent 知识库绑定的可见性与运行时过滤。所属域：rag 的 application。④ 期验收主用例。
 * <p>
 * 覆盖点：
 * <ol>
 *   <li>未授权（PRIVATE 非创建人、非管理员）不能绑，报 FORBIDDEN；</li>
 *   <li>RESTRICTED / LOGICAL / INIT_FAILED 不能绑，报明确原因；</li>
 *   <li>运行时过滤：绑定停用 → 不进列表；库初始化失败 → 不进列表；</li>
 *   <li>可用链路：绑定启用 + 库 READY → 能取到，且覆盖项能取到。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentKbBindAppServiceTest {

    @Mock
    private AgentDefRepository agents;
    @Mock
    private RagKnowledgeBaseRepository kbs;
    @Mock
    private AgentKbBindRepository binds;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private RagAdminJudge adminJudge;

    private AgentKbBindAppService service;

    @BeforeEach
    void setUp() {
        service = new AgentKbBindAppService(agents, kbs, binds, operatorContext, operLogWriter, adminJudge);
        lenient().when(operatorContext.requireUserId()).thenReturn(9L);
        lenient().when(operatorContext.userIdOrNull()).thenReturn(9L);
        lenient().when(adminJudge.isPrivileged()).thenReturn(false);
        AgentDef agent = new AgentDef();
        agent.setId(7L);
        when(agents.findById(7L)).thenReturn(Optional.of(agent));
    }

    /**
     * PRIVATE 库 + 非创建人 + 非管理员 → FORBIDDEN，且不写绑定。
     */
    @Test
    void rejectsPrivateKbForOtherUser() {
        RagKnowledgeBase kb = kb("READY", "PRIVATE", "PHYSICAL");
        kb.setCreatedBy(999L);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb));

        assertThrows(BizException.class, () -> service.create(7L, req(1L)));
        verify(binds, never()).insert(any(), anyLong());
    }

    /**
     * 管理员可绑 PRIVATE 库。
     */
    @Test
    void allowsPrivateKbForAdmin() {
        when(adminJudge.isPrivileged()).thenReturn(true);
        RagKnowledgeBase kb = kb("READY", "PRIVATE", "PHYSICAL");
        kb.setCreatedBy(999L);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb));
        when(binds.existsPair(7L, 1L, null)).thenReturn(false);
        when(binds.listByAgentId(7L)).thenReturn(List.of());
        when(binds.insert(any(), anyLong())).thenReturn(500L);
        when(binds.findById(500L)).thenReturn(Optional.of(bind(500L, 1L, 1)));

        KbBindResp resp = service.create(7L, req(1L));

        assertEquals(500L, resp.getId());
        verify(binds).insert(any(), eq(9L));
    }

    /**
     * PUBLIC 库对任意登录用户可见。
     */
    @Test
    void allowsPublicKbForAnyone() {
        RagKnowledgeBase kb = kb("READY", "PUBLIC", "PHYSICAL");
        kb.setCreatedBy(999L);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb));
        when(binds.existsPair(7L, 1L, null)).thenReturn(false);
        when(binds.listByAgentId(7L)).thenReturn(List.of());
        when(binds.insert(any(), anyLong())).thenReturn(501L);
        when(binds.findById(501L)).thenReturn(Optional.of(bind(501L, 1L, 1)));

        assertEquals(501L, service.create(7L, req(1L)).getId());
    }

    /**
     * RESTRICTED 一期拒绝，避免建出「看着私密其实全开」的绑定。
     */
    @Test
    void rejectsRestrictedKb() {
        when(adminJudge.isPrivileged()).thenReturn(true);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("READY", "RESTRICTED", "PHYSICAL")));

        BizException ex = assertThrows(BizException.class, () -> service.create(7L, req(1L)));
        assertTrue(ex.getMessage().contains("指定授权"));
        verify(binds, never()).insert(any(), anyLong());
    }

    /**
     * 初始化失败的知识库不能绑：没有集合，绑了也查不到。
     */
    @Test
    void rejectsInitFailedKb() {
        when(adminJudge.isPrivileged()).thenReturn(true);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("INIT_FAILED", "PUBLIC", "PHYSICAL")));

        BizException ex = assertThrows(BizException.class, () -> service.create(7L, req(1L)));
        assertTrue(ex.getMessage().contains("初始化失败"));
    }

    /**
     * LOGICAL 一期不落地，拒绝。
     */
    @Test
    void rejectsLogicalKb() {
        when(adminJudge.isPrivileged()).thenReturn(true);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("READY", "PUBLIC", "LOGICAL")));

        BizException ex = assertThrows(BizException.class, () -> service.create(7L, req(1L)));
        assertTrue(ex.getMessage().contains("逻辑隔离"));
    }

    /**
     * 重复绑定被拒。
     */
    @Test
    void rejectsDuplicatePair() {
        when(adminJudge.isPrivileged()).thenReturn(true);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("READY", "PUBLIC", "PHYSICAL")));
        when(binds.existsPair(7L, 1L, null)).thenReturn(true);

        assertThrows(BizException.class, () -> service.create(7L, req(1L)));
    }

    /**
     * 运行时过滤：停用的绑定不进列表。
     */
    @Test
    void disabledBindingNotUsable() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(500L, 1L, 0)));
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("READY", "PUBLIC", "PHYSICAL")));

        assertTrue(service.listUsableForAgent(7L).isEmpty());
    }

    /**
     * 运行时过滤：库初始化失败即使绑定开着也不进列表。
     */
    @Test
    void unusableKbNotUsableEvenIfBound() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(500L, 1L, 1)));
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("INIT_FAILED", "PUBLIC", "PHYSICAL")));

        assertTrue(service.listUsableForAgent(7L).isEmpty());
    }

    /**
     * 运行时过滤：库被删（findById 空）同样跳过，不抛错。
     */
    @Test
    void missingKbSkippedSilently() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(500L, 1L, 1)));
        when(kbs.findById(1L)).thenReturn(Optional.empty());

        assertTrue(service.listUsableForAgent(7L).isEmpty());
    }

    /**
     * 正常链路：启用绑定 + READY 库 → 出现在可用列表，覆盖项可取。
     */
    @Test
    void usableListContainsReadyKb() {
        when(binds.listByAgentId(7L)).thenReturn(List.of(bind(500L, 1L, 1)));
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("READY", "PUBLIC", "PHYSICAL")));

        List<RagKnowledgeBase> usable = service.listUsableForAgent(7L);

        assertEquals(1, usable.size());
        assertEquals(1L, usable.get(0).getId());
        assertEquals("agenthub_kb_1", usable.get(0).getCollectionName());
    }

    /**
     * 覆盖项：只有启用绑定才返回，停用绑定的覆盖不生效。
     */
    @Test
    void overridesOnlyForEnabledBinds() {
        AgentKbBind enabled = bind(500L, 1L, 1);
        enabled.setOverridesJson("{\"topK\":3}");
        AgentKbBind disabled = bind(501L, 2L, 0);
        disabled.setOverridesJson("{\"topK\":9}");
        when(binds.listByAgentId(7L)).thenReturn(List.of(enabled, disabled));

        var map = service.overridesFor(7L);

        assertEquals(1, map.size());
        assertEquals("{\"topK\":3}", map.get(1L));
    }

    /**
     * 覆盖项非法 JSON：拒绝保存。
     */
    @Test
    void rejectsInvalidOverridesJson() {
        when(adminJudge.isPrivileged()).thenReturn(true);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("READY", "PUBLIC", "PHYSICAL")));
        when(binds.existsPair(7L, 1L, null)).thenReturn(false);
        when(binds.listByAgentId(7L)).thenReturn(List.of());
        KbBindCreateReq req = req(1L);
        req.setOverridesJson("not-json");

        assertThrows(BizException.class, () -> service.create(7L, req));
        verify(binds, never()).insert(any(), anyLong());
    }

    /**
     * 超过单 Agent 绑定上限：拒绝。
     */
    @Test
    void rejectsWhenExceedingMaxBinds() {
        when(adminJudge.isPrivileged()).thenReturn(true);
        when(kbs.findById(1L)).thenReturn(Optional.of(kb("READY", "PUBLIC", "PHYSICAL")));
        when(binds.existsPair(7L, 1L, null)).thenReturn(false);
        List<AgentKbBind> many = new java.util.ArrayList<>();
        for (int i = 0; i < AgentKbBindAppService.MAX_BINDS_PER_AGENT; i++) {
            many.add(bind((long) (600 + i), (long) (10 + i), 1));
        }
        when(binds.listByAgentId(7L)).thenReturn(many);

        assertThrows(BizException.class, () -> service.create(7L, req(1L)));
    }

    private KbBindCreateReq req(Long kbId) {
        KbBindCreateReq req = new KbBindCreateReq();
        req.setKbId(kbId);
        return req;
    }

    private RagKnowledgeBase kb(String status, String visibility, String isolation) {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(1L);
        kb.setTenantId(0L);
        kb.setName("测试库");
        kb.setStatus(status);
        kb.setVisibility(visibility);
        kb.setIsolationMode(isolation);
        kb.setCollectionName("agenthub_kb_1");
        kb.setStoreCapability("DENSE_ONLY");
        kb.setEmbeddingModelId(8L);
        kb.setVectorDimension(8);
        kb.setCreatedBy(9L);
        return kb;
    }

    private AgentKbBind bind(Long id, Long kbId, int status) {
        AgentKbBind bind = new AgentKbBind();
        bind.setId(id);
        bind.setAgentId(7L);
        bind.setKbId(kbId);
        bind.setStatus(status);
        return bind;
    }
}

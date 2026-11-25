package com.agenthub.agent.application;

import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.domain.service.MaTenantProvider;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 默认接待就绪判定。所属域：agent。
 *
 * <p>重点是「不自动挑别的方案」：未配置、停用、无候选、无判路由模型都必须 ready=false，
 * 并给出可直接展示的中文原因。模型实例一律 mock，不打真实供应商。
 */
@ExtendWith(MockitoExtension.class)
class ReceptionSchemeResolverTest {

    @Mock
    private MaDefRepository mas;
    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private MaTenantProvider tenantProvider;

    private ReceptionSchemeResolver resolver;

    @BeforeEach
    void setUp() {
        when(tenantProvider.currentTenantId()).thenReturn(1L);
        resolver = new ReceptionSchemeResolver(mas, instances, tenantProvider);
    }

    /** 未配置默认接待：def 为空，ready=false，文案提示去设默认或跑种子。 */
    @Test
    void reportsNotConfigured() {
        when(mas.findReceptionDefault(1L)).thenReturn(Optional.empty());

        ReceptionSchemeResolver.Snapshot snapshot = resolver.snapshot();

        assertFalse(snapshot.ready());
        assertNull(snapshot.def());
        assertTrue(snapshot.candidates().isEmpty());
        assertTrue(snapshot.reason().contains("默认接待"));
    }

    /** 默认方案被停用：即使有候选也不能开始对话。 */
    @Test
    void reportsDisabledScheme() {
        MaDef def = def(10L, 0, null);
        when(mas.findReceptionDefault(1L)).thenReturn(Optional.of(def));
        when(mas.listCandidates(10L)).thenReturn(List.of(candidate(1L, 100L)));

        ReceptionSchemeResolver.Snapshot snapshot = resolver.snapshot();

        assertFalse(snapshot.ready());
        assertTrue(snapshot.reason().contains("已停用"));
    }

    /** 候选全被停用：视为没有可用子 Agent，而不是只留一条空方案。 */
    @Test
    void reportsNoEnabledCandidate() {
        MaDef def = def(10L, 1, null);
        MaCandidate disabled = candidate(1L, 100L);
        disabled.setEnabled(0);
        when(mas.findReceptionDefault(1L)).thenReturn(Optional.of(def));
        when(mas.listCandidates(10L)).thenReturn(List.of(disabled));

        ReceptionSchemeResolver.Snapshot snapshot = resolver.snapshot();

        assertFalse(snapshot.ready());
        assertTrue(snapshot.reason().contains("子 Agent"));
    }

    /** 方案没配判路由模型：回落平台 ROUTER → CHAT 槽，ROUTER 为空时用 CHAT。 */
    @Test
    void fallsBackToPlatformChatSlot() {
        MaDef def = def(10L, 1, null);
        when(mas.findReceptionDefault(1L)).thenReturn(Optional.of(def));
        when(mas.listCandidates(10L)).thenReturn(List.of(candidate(1L, 100L)));
        when(instances.listEnabledByCapability("ROUTER")).thenReturn(List.of());
        when(instances.listEnabledByCapability("CHAT"))
                .thenReturn(List.of(instance(5L, "CHAT", 1)));

        ReceptionSchemeResolver.Snapshot snapshot = resolver.snapshot();

        assertTrue(snapshot.ready());
        assertNull(snapshot.reason());
        assertEquals(5L, snapshot.judgeModelInstanceId());
    }

    /** 平台没有任何对话形态模型：ready=false，提示去模型中心。 */
    @Test
    void reportsMissingJudgeModel() {
        MaDef def = def(10L, 1, null);
        when(mas.findReceptionDefault(1L)).thenReturn(Optional.of(def));
        when(mas.listCandidates(10L)).thenReturn(List.of(candidate(1L, 100L)));
        when(instances.listEnabledByCapability(anyString())).thenReturn(List.of());

        ReceptionSchemeResolver.Snapshot snapshot = resolver.snapshot();

        assertFalse(snapshot.ready());
        assertTrue(snapshot.reason().contains("模型中心"));
    }

    /** 方案显式配了判路由模型但实例不存在：不能装作可用。 */
    @Test
    void reportsConfiguredJudgeMissing() {
        MaDef def = def(10L, 1, "{\"maJudgeModelInstanceId\":99}");
        when(mas.findReceptionDefault(1L)).thenReturn(Optional.of(def));
        when(mas.listCandidates(10L)).thenReturn(List.of(candidate(1L, 100L)));
        when(instances.findById(99L)).thenReturn(Optional.empty());

        ReceptionSchemeResolver.Snapshot snapshot = resolver.snapshot();

        assertFalse(snapshot.ready());
        assertTrue(snapshot.reason().contains("判路由模型"));
    }

    /** 方案显式配了可用实例：直接使用方案值，不再回落平台槽。 */
    @Test
    void usesConfiguredJudgeModel() {
        MaDef def = def(10L, 1, "{\"maJudgeModelInstanceId\":77}");
        when(mas.findReceptionDefault(1L)).thenReturn(Optional.of(def));
        when(mas.listCandidates(10L)).thenReturn(List.of(candidate(1L, 100L)));
        when(instances.findById(77L)).thenReturn(Optional.of(instance(77L, "ROUTER", 1)));

        ReceptionSchemeResolver.Snapshot snapshot = resolver.snapshot();

        assertTrue(snapshot.ready());
        assertEquals(77L, snapshot.judgeModelInstanceId());
    }

    /** 最小可用方案：启用 + 一条启用候选。 */
    private static MaDef def(Long id, int enabled, String configJson) {
        MaDef def = new MaDef();
        def.setId(id);
        def.setTenantId(1L);
        def.setCode("reception_default");
        def.setName("默认接待");
        def.setTemplate("ROUTER_PARALLEL");
        def.setConfigJson(configJson);
        def.setEnabled(enabled);
        def.setIsReceptionDefault(1);
        return def;
    }

    private static MaCandidate candidate(Long id, Long agentId) {
        MaCandidate candidate = new MaCandidate();
        candidate.setId(id);
        candidate.setMaId(10L);
        candidate.setAgentId(agentId);
        candidate.setRouteKey("reception_assistant");
        candidate.setSortNo(1);
        candidate.setEnabled(1);
        return candidate;
    }

    private static ModelInstance instance(Long id, String capability, int status) {
        ModelInstance instance = new ModelInstance();
        instance.setId(id);
        instance.setCapability(capability);
        instance.setStatus(status);
        return instance;
    }
}

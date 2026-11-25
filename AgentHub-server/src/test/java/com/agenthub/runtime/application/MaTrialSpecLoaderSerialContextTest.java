package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.domain.service.MaTenantProvider;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 运行装载遇到历史「串行 + 隔离」时收口成继承，不把保存拒绝语抛成整轮失败。
 *
 * <p>所属域：runtime。入口和意图路由分发保持原策略，避免被收成路由后串行。
 */
@ExtendWith(MockitoExtension.class)
class MaTrialSpecLoaderSerialContextTest {

    @Mock
    private MaDefRepository mas;
    @Mock
    private MaTenantProvider tenantProvider;
    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private WorkflowRunSpecLoader workflowTargets;

    private MaTrialSpecLoader loader;

    @BeforeEach
    void setUp() {
        loader = new MaTrialSpecLoader(mas, tenantProvider, instances, workflowTargets);
        when(tenantProvider.currentTenantId()).thenReturn(1L);
        when(workflowTargets.load(anyList(), anyLong())).thenReturn(Map.<Long, WorkflowRunSpec>of());
    }

    /**
     * 固定串行列 + 配置里的隔离：装载成功，模板收成路由后串行，策略收成继承，交接仍开。
     */
    @Test
    void legacySerialIsolatedLoadsAsPriorSummary() {
        when(mas.findById(10L)).thenReturn(Optional.of(scheme(10L, "FIXED_SEQUENCE",
                "{\"maTemplate\":\"FIXED_SEQUENCE\",\"maContextPolicy\":\"ISOLATED\"}")));
        when(mas.listCandidates(10L)).thenReturn(List.of());

        MaRunSpec spec = assertDoesNotThrow(() -> loader.requireForCreate(10L));

        assertEquals(AgentRuntimeConfig.TEMPLATE_ROUTER_THEN_SEQUENCE, spec.runtime().getMaTemplate());
        assertEquals(AgentRuntimeConfig.CONTEXT_PRIOR_SUMMARY_ONLY, spec.runtime().getMaContextPolicy());
        assertTrue(spec.runtime().priorSummaryOn());
    }

    /**
     * 意图路由分发即使配置写着隔离，也保持隔离，并且不走串行交接。
     */
    @Test
    void parallelIsolatedStaysIsolated() {
        when(mas.findById(11L)).thenReturn(Optional.of(scheme(11L, "ROUTER_PARALLEL",
                "{\"maTemplate\":\"FIXED_SEQUENCE\",\"maContextPolicy\":\"ISOLATED\"}")));
        when(mas.listCandidates(11L)).thenReturn(List.of());

        MaRunSpec spec = loader.requireForCreate(11L);

        assertEquals(AgentRuntimeConfig.TEMPLATE_ROUTER_PARALLEL, spec.runtime().getMaTemplate());
        assertEquals(AgentRuntimeConfig.CONTEXT_ISOLATED, spec.runtime().getMaContextPolicy());
        assertFalse(spec.runtime().priorSummaryOn());
    }

    /**
     * 入口列是 ENTRY_ROUTER 时，配置 JSON 里残留的串行码不能把入口收成路由后串行。
     * 池里的历史串行子方案收口后仍能装进来，不把拒绝语冒泡成建会话失败。
     */
    @Test
    void entryStaysEntryWhileNestedSerialIsolatedIsCoerced() {
        MaDef entry = scheme(1L, AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER,
                "{\"maTemplate\":\"ROUTER_THEN_SEQUENCE\",\"maContextPolicy\":\"ISOLATED\"}");
        MaDef child = scheme(20L, "FIXED_SEQUENCE",
                "{\"maTemplate\":\"FIXED_SEQUENCE\",\"maContextPolicy\":\"SHARED_SCRATCH\"}");
        when(mas.findById(1L)).thenReturn(Optional.of(entry));
        when(mas.findById(20L)).thenReturn(Optional.of(child));
        when(mas.listCandidates(1L)).thenReturn(List.of(maTarget(20L)));
        when(mas.listCandidates(20L)).thenReturn(List.of());

        MaRunSpec spec = assertDoesNotThrow(() -> loader.requireForWorkbench(1L));

        assertEquals(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER, spec.runtime().getMaTemplate());
        assertEquals(AgentRuntimeConfig.CONTEXT_ISOLATED, spec.runtime().getMaContextPolicy());
        assertFalse(spec.runtime().priorSummaryOn());
        MaRunSpec nested = spec.maTarget(20L).orElseThrow();
        assertEquals(AgentRuntimeConfig.TEMPLATE_ROUTER_THEN_SEQUENCE, nested.runtime().getMaTemplate());
        assertEquals(AgentRuntimeConfig.CONTEXT_PRIOR_SUMMARY_ONLY, nested.runtime().getMaContextPolicy());
        assertTrue(nested.runtime().priorSummaryOn());
    }

    /** 启用中的本租户方案。模板列与配置 JSON 故意可以不一致，用来核对以哪一边为准。 */
    private static MaDef scheme(long id, String template, String configJson) {
        MaDef def = new MaDef();
        def.setId(id);
        def.setTenantId(1L);
        def.setCode("s" + id);
        def.setName("方案" + id);
        def.setTemplate(template);
        def.setConfigJson(configJson);
        def.setEnabled(1);
        return def;
    }

    /** 指向另一套方案的启用候选。 */
    private static MaCandidate maTarget(long targetId) {
        MaCandidate candidate = new MaCandidate();
        candidate.setTargetType(MaCandidate.TARGET_MA);
        candidate.setMaDefId(targetId);
        candidate.setRouteKey("child-" + targetId);
        candidate.setEnabled(1);
        return candidate;
    }
}

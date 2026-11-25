package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.ReceptionAgentOptionResp;
import com.agenthub.agent.api.dto.ReceptionCandidateAddReq;
import com.agenthub.agent.api.dto.ReceptionCandidateEditReq;
import com.agenthub.agent.api.dto.ReceptionConfigResp;
import com.agenthub.agent.api.dto.ReceptionSchemeOptionResp;
import com.agenthub.agent.api.dto.ReceptionWorkflowOptionResp;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.domain.service.MaTenantProvider;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 默认接待简易配置。所属域：agent。
 *
 * <p>重点验收的硬口径：
 * <ul>
 *   <li>已被任一方案占用的 Agent 不出现在可选列表 / 不能被添加；</li>
 *   <li>添加时复用现网子类型门闩，不合法的子 Agent 直接拒绝；</li>
 *   <li>刀4：意图说明只来自 Agent 简介——空简介不能入池，改候选 intent_desc 一律拒绝，
 *       改接待提示词接口明确失败。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReceptionConfigAppServiceTest {

    @Mock
    private MaDefRepository mas;
    @Mock
    private AgentDefRepository agents;
    @Mock
    private ReceptionSchemeResolver reception;
    @Mock
    private MaTenantProvider tenantProvider;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private WorkflowDefRepository workflowDefs;

    private ReceptionConfigAppService service;

    @BeforeEach
    void setUp() {
        service = new ReceptionConfigAppService(mas, agents, reception, tenantProvider,
                operatorContext, operLogWriter, workflowDefs);
    }

    /** 可选列表：剔除已被任一方案占用的 Agent，也不列本方案不接受的子模式。 */
    @Test
    void availableAgentsExcludesUsedAndUnsupportedModes() {
        when(reception.usedAgentIds()).thenReturn(List.of(2L));
        when(reception.snapshot()).thenReturn(snapshot(null, List.of()));
        when(agents.listOrchestrable()).thenReturn(List.of(
                agent(1L, "订单助手", "order_bot", "REACT", 1),
                agent(2L, "占用助手", "used_bot", "REACT", 1),
                agent(3L, "计划助手", "plan_bot", "PLAN_AND_EXECUTE", 1)));

        List<ReceptionAgentOptionResp> rows = service.availableAgents();

        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).getId());
    }

    /** 已被别的方案列为候选：添加直接 409，且不落库。 */
    @Test
    void addCandidateRejectsAgentUsedByOtherScheme() {
        when(reception.snapshot()).thenReturn(snapshot(def(42L), List.of()));
        when(agents.findById(2L)).thenReturn(java.util.Optional.of(agent(2L, "占用助手", "used_bot", "REACT", 1)));
        when(reception.usedAgentIds()).thenReturn(List.of(2L));

        BizException ex = assertThrows(BizException.class, () -> service.addCandidate(addReq(2L)));

        assertEquals(ErrorCode.DUPLICATE, ex.getErrorCode());
        verify(mas, never()).insertCandidate(any());
    }

    /** 合法子 Agent：按 max+10 追加排序，意图说明用该 Agent 的简介回填。 */
    @Test
    void addCandidateAppendsEligibleChild() {
        MaDef def = def(42L);
        AgentDef child = agent(100L, "订单助手", "order_bot", "REACT", 1);
        child.setDescription("查询订单、物流与退款进度");
        when(reception.snapshot()).thenReturn(snapshot(def, List.of()));
        when(agents.findById(100L)).thenReturn(java.util.Optional.of(child));
        when(reception.usedAgentIds()).thenReturn(List.of());
        // 第一次是添加前查重，第二次是 config() 回显；回显里带上刚插入的行
        MaCandidate stored = candidate(55L, 100L, 1);
        when(mas.listCandidates(42L)).thenReturn(List.of(), List.of(stored));
        when(agents.findByIds(List.of(100L))).thenReturn(List.of(child));

        ReceptionConfigResp resp = service.addCandidate(addReq(100L));

        ArgumentCaptor<MaCandidate> captor = ArgumentCaptor.forClass(MaCandidate.class);
        verify(mas).insertCandidate(captor.capture());
        MaCandidate inserted = captor.getValue();
        assertEquals(100L, inserted.getAgentId());
        assertEquals(1, inserted.getEnabled());
        // 列里回填的是 Agent 简介，不再接受请求自带的意图文案
        assertEquals("查询订单、物流与退款进度", inserted.getIntentDesc());
        assertEquals(1, resp.getCandidates().size());
        assertEquals("订单助手", resp.getCandidates().get(0).getAgentName());
        assertTrue(resp.getCandidates().get(0).isReceptionAssistant());
    }

    /**
     * 刀4：简介为空的 Agent 不能加入路由池。
     *
     * <p>没有简介就判不出路由，必须在 Agent 管理先补齐；不能静默用空描述入池。
     */
    @Test
    void addCandidateRejectsAgentWithoutDescription() {
        AgentDef blank = agent(100L, "订单助手", "order_bot", "REACT", 1);
        blank.setDescription("   ");
        when(reception.snapshot()).thenReturn(snapshot(def(42L), List.of()));
        when(agents.findById(100L)).thenReturn(java.util.Optional.of(blank));
        when(reception.usedAgentIds()).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> service.addCandidate(addReq(100L)));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("简介"), ex.getMessage());
        verify(mas, never()).insertCandidate(any());
    }

    /**
     * 刀4：候选意图说明不可单独修改（它只来自 Agent 简介）。
     *
     * <p>旧前端还会传 intentDesc，必须明确失败而不是静默忽略。
     */
    @Test
    void updateCandidateRejectsIntentDesc() {
        MaCandidate existing = candidate(55L, 100L, 1);
        when(reception.snapshot()).thenReturn(snapshot(def(42L), List.of(existing)));
        when(mas.listCandidates(42L)).thenReturn(List.of(existing));
        ReceptionCandidateEditReq req = new ReceptionCandidateEditReq();
        req.setIntentDesc("新意图说明");

        BizException ex = assertThrows(BizException.class, () -> service.updateCandidate(55L, req));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Agent 简介"), ex.getMessage());
        verify(mas, never()).updateCandidate(anyLong(), anyLong(), any(), any(), any());
    }

    /**
     * 刀4：默认接待页不再维护接待提示词，旧接口固定明确失败。
     *
     * <p>人设只配在接待助手 Agent 的系统提示词里；这里不能「保存成功但什么也没变」。
     */
    @Test
    void updatePromptIsRejectedExplicitly() {
        BizException ex = assertThrows(BizException.class, () -> service.updatePrompt("你是接待助手"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Agent 管理"), ex.getMessage());
    }

    /**
     * 刀5：添加「另一套编排方案」——按方案说明回填意图说明，落 target_type=MA 且不带 agentId。
     */
    @Test
    void addSchemeCandidateWritesMaTarget() {
        MaDef def = def(42L);
        MaDef target = scheme(77L, "after_sale", "售后编排", "售后问题统一编排", 1, null);
        when(reception.snapshot()).thenReturn(snapshot(def, List.of()));
        when(mas.findById(77L)).thenReturn(java.util.Optional.of(target));
        when(mas.listCandidates(42L)).thenReturn(List.of(), List.of(maCandidate(56L, 77L, 1)));
        when(mas.findByIds(List.of(77L))).thenReturn(List.of(target));
        when(tenantProvider.currentTenantId()).thenReturn(1L);

        ReceptionCandidateAddReq req = new ReceptionCandidateAddReq();
        req.setMaDefId(77L);
        ReceptionConfigResp resp = service.addCandidate(req);

        ArgumentCaptor<MaCandidate> captor = ArgumentCaptor.forClass(MaCandidate.class);
        verify(mas).insertCandidate(captor.capture());
        MaCandidate inserted = captor.getValue();
        assertEquals(MaCandidate.TARGET_MA, inserted.getTargetType());
        assertEquals(77L, inserted.getMaDefId());
        assertNull(inserted.getAgentId());
        assertEquals("售后问题统一编排", inserted.getIntentDesc());
        // 路由键优先用方案编码（人可读，且刀3 的 chip / 换线只暴露展示名，不暴露键）
        assertEquals("after_sale", inserted.getRouteKey());
        assertEquals(1, resp.getCandidates().size());
        assertEquals("售后编排", resp.getCandidates().get(0).getMaName());
        assertEquals("售后问题统一编排", resp.getCandidates().get(0).getIntentDesc());
    }

    /** 刀5：默认接待不能把「默认接待」方案或自己加进池。 */
    @Test
    void addSchemeCandidateRejectsReceptionDefault() {
        MaDef def = def(42L);
        MaDef other = scheme(77L, "reception_default", "另一个默认接待", "不该被加", 1, 1);
        when(reception.snapshot()).thenReturn(snapshot(def, List.of()));
        when(mas.listCandidates(42L)).thenReturn(List.of());
        when(mas.findById(77L)).thenReturn(java.util.Optional.of(other));
        when(tenantProvider.currentTenantId()).thenReturn(1L);

        ReceptionCandidateAddReq req = new ReceptionCandidateAddReq();
        req.setMaDefId(77L);
        BizException ex = assertThrows(BizException.class, () -> service.addCandidate(req));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("默认接待"), ex.getMessage());
        verify(mas, never()).insertCandidate(any());
    }

    /** 刀5：方案说明为空不能入池。 */
    @Test
    void addSchemeCandidateRejectsBlankDescription() {
        MaDef def = def(42L);
        MaDef target = scheme(77L, "after_sale", "售后编排", "  ", 1, null);
        when(reception.snapshot()).thenReturn(snapshot(def, List.of()));
        when(mas.listCandidates(42L)).thenReturn(List.of());
        when(mas.findById(77L)).thenReturn(java.util.Optional.of(target));
        when(tenantProvider.currentTenantId()).thenReturn(1L);

        ReceptionCandidateAddReq req = new ReceptionCandidateAddReq();
        req.setMaDefId(77L);
        BizException ex = assertThrows(BizException.class, () -> service.addCandidate(req));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("方案说明"), ex.getMessage());
        verify(mas, never()).insertCandidate(any());
    }

    /** 可加方案下拉剔除自己 / 默认接待 / 停用 / 已在池里。嵌套方案和历史串行模板可以入池。 */
    @Test
    void availableSchemesFiltersIneligibleTargets() {
        MaDef self = def(42L);
        self.setIsReceptionDefault(1);
        when(reception.snapshot()).thenReturn(snapshot(self, List.of()));
        when(tenantProvider.currentTenantId()).thenReturn(1L);
        when(mas.listByTenant(1L)).thenReturn(List.of(
                self,
                scheme(70L, "after_sale", "售后编排", "售后问题统一编排", 1, null),
                scheme(71L, "other_default", "另一个默认接待", "不该出现", 1, 1),
                scheme(72L, "off", "已停用", "也不该出现", 0, null),
                scheme(73L, "fixed", "固定串行", "不判路由", 1, null),
                scheme(75L, "in_pool", "已在池里", "不该重复出现", 1, null),
                scheme(74L, "nested", "外层", "自己还挂着方案", 1, null)));
        // 默认接待池里已经有 75 号：下拉不再重复列出（选了也只会 400）
        when(mas.listCandidates(42L)).thenReturn(List.of(maCandidate(56L, 75L, 1)));

        List<ReceptionSchemeOptionResp> rows = service.availableSchemes();

        assertEquals(List.of(70L, 73L, 74L), rows.stream().map(ReceptionSchemeOptionResp::getId).toList());
        assertEquals("售后问题统一编排", rows.get(0).getDescription());
    }

    private static ReceptionCandidateAddReq addReq(Long agentId) {
        ReceptionCandidateAddReq req = new ReceptionCandidateAddReq();
        req.setAgentId(agentId);
        return req;
    }

    /**
     * 刀5B：可加工作流下拉只出「已发布 + 有默认发布指针 + 有说明 + 不在池里」的工作流。
     *
     * <p>仓储侧已经把这四条写成 SQL，这里断言的是「服务层不再把已在池里的重复列出」。
     */
    @Test
    void availableWorkflowsExcludesInPoolTargets() {
        when(reception.snapshot()).thenReturn(snapshot(def(42L), List.of()));
        when(tenantProvider.currentTenantId()).thenReturn(1L);
        when(workflowDefs.listRoutable(1L, 200)).thenReturn(List.of(
                workflow(88L, "reimburse", "报销审批", "处理报销申请与审批流转", "HAS_RELEASE", 900L),
                workflow(89L, "in_pool", "已在池里", "不该重复出现", "HAS_RELEASE", 901L)));
        when(mas.listCandidates(42L)).thenReturn(List.of(workflowCandidate(57L, 89L, 2)));

        List<ReceptionWorkflowOptionResp> rows = service.availableWorkflows();

        assertEquals(1, rows.size());
        assertEquals(88L, rows.get(0).getId());
        assertEquals("报销审批", rows.get(0).getName());
    }

    /** 刀5B：草稿-only / 无默认发布的工作流加不进池，且不落库。 */
    @Test
    void addWorkflowCandidateRejectsUnpublishedTarget() {
        when(reception.snapshot()).thenReturn(snapshot(def(42L), List.of()));
        when(mas.listCandidates(42L)).thenReturn(List.of());
        when(tenantProvider.currentTenantId()).thenReturn(1L);
        when(workflowDefs.findById(88L)).thenReturn(java.util.Optional.of(
                workflow(88L, "reimburse", "报销审批", "处理报销申请", "DRAFT", null)));

        ReceptionCandidateAddReq req = new ReceptionCandidateAddReq();
        req.setWorkflowDefId(88L);
        BizException ex = assertThrows(BizException.class, () -> service.addCandidate(req));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("发布"), ex.getMessage());
        verify(mas, never()).insertCandidate(any());
    }

    /** 刀5B：已发布且有说明的工作流可以入池，路由键按编码派生并回填说明。 */
    @Test
    void addWorkflowCandidatePersistsWithDerivedKey() {
        MaDef def = def(42L);
        when(reception.snapshot()).thenReturn(snapshot(def, List.of()));
        when(mas.listCandidates(42L)).thenReturn(List.of());
        when(tenantProvider.currentTenantId()).thenReturn(1L);
        when(workflowDefs.findById(88L)).thenReturn(java.util.Optional.of(
                workflow(88L, "reimburse", "报销审批", "处理报销申请与审批流转", "HAS_RELEASE", 900L)));

        ReceptionCandidateAddReq req = new ReceptionCandidateAddReq();
        req.setWorkflowDefId(88L);
        service.addCandidate(req);

        ArgumentCaptor<MaCandidate> captor = ArgumentCaptor.forClass(MaCandidate.class);
        verify(mas).insertCandidate(captor.capture());
        MaCandidate saved = captor.getValue();
        assertEquals(MaCandidate.TARGET_WORKFLOW, saved.getTargetType());
        assertEquals(88L, saved.getWorkflowDefId());
        // 路由键优先用工作流编码，界面只展示名称，不暴露这个 key
        assertEquals("reimburse", saved.getRouteKey());
        // 旧列仍回填同一份说明，但判路由读的是 workflow_def.description
        assertEquals("处理报销申请与审批流转", saved.getIntentDesc());
    }

    private static ReceptionSchemeResolver.Snapshot snapshot(MaDef def, List<MaCandidate> candidates) {
        return new ReceptionSchemeResolver.Snapshot(def, candidates, def != null, null, 9L);
    }

    private static MaDef def(Long id) {
        MaDef def = new MaDef();
        def.setId(id);
        def.setTenantId(1L);
        def.setCode("reception_default");
        def.setName("默认接待");
        def.setTemplate("ROUTER_PARALLEL");
        def.setConfigJson("{\"maChildRunModesAllowed\":[\"REACT\"]}");
        def.setEnabled(1);
        return def;
    }

    private static MaCandidate candidate(Long id, Long agentId, int sortNo) {
        MaCandidate candidate = new MaCandidate();
        candidate.setId(id);
        candidate.setMaId(42L);
        candidate.setAgentId(agentId);
        candidate.setRouteKey("order_bot");
        candidate.setIntentDesc("订单助手 可处理的请求");
        candidate.setSortNo(sortNo);
        candidate.setEnabled(1);
        return candidate;
    }

    /** 刀5：MA 候选行（无子 Agent，按 route_key 定位）。 */
    private static MaCandidate maCandidate(Long id, Long targetMaId, int sortNo) {
        MaCandidate candidate = new MaCandidate();
        candidate.setId(id);
        candidate.setMaId(42L);
        candidate.setTargetType(MaCandidate.TARGET_MA);
        candidate.setMaDefId(targetMaId);
        candidate.setRouteKey("ma_after_sale");
        candidate.setSortNo(sortNo);
        candidate.setEnabled(1);
        return candidate;
    }

    /** 刀5B：WORKFLOW 候选行（无子 Agent，按 route_key 定位）。 */
    private static MaCandidate workflowCandidate(Long id, Long workflowDefId, int sortNo) {
        MaCandidate candidate = new MaCandidate();
        candidate.setId(id);
        candidate.setMaId(42L);
        candidate.setTargetType(MaCandidate.TARGET_WORKFLOW);
        candidate.setWorkflowDefId(workflowDefId);
        candidate.setRouteKey("wf_" + workflowDefId);
        candidate.setSortNo(sortNo);
        candidate.setEnabled(1);
        return candidate;
    }

    /** 刀5B：工作流定义（下拉与门闩共用）。 */
    private static WorkflowDef workflow(Long id, String code, String name, String description,
                                        String status, Long defaultReleaseId) {
        WorkflowDef def = new WorkflowDef();
        def.setId(id);
        def.setTenantId(1L);
        def.setCode(code);
        def.setName(name);
        def.setDescription(description);
        def.setStatus(status);
        def.setDefaultReleaseId(defaultReleaseId);
        return def;
    }

    /** 刀5：目标方案。receptionDefault=1 表示平台默认接待。 */
    private static MaDef scheme(Long id, String code, String name, String description,
                                int enabled, Integer receptionDefault) {
        MaDef def = new MaDef();
        def.setId(id);
        def.setTenantId(1L);
        def.setCode(code);
        def.setName(name);
        def.setDescription(description);
        // 只有会判路由的模板才能被加进池；73 号用固定串行模拟「不判路由」
        def.setTemplate(id != null && id == 73L ? "FIXED_SEQUENCE" : "ROUTER_PARALLEL");
        def.setEnabled(enabled);
        def.setIsReceptionDefault(receptionDefault);
        return def;
    }

    private static AgentDef agent(Long id, String name, String code, String runMode, int status) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode(code);
        agent.setRunMode(runMode);
        agent.setStatus(status);
        agent.setAllowOrchestrated(1);
        // 刀4 起简介是意图说明唯一来源，默认给一条，特例再单独覆盖
        agent.setDescription("负责 " + name + " 的常见请求");
        return agent;
    }
}

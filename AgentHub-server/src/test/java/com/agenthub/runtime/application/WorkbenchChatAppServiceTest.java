package com.agenthub.runtime.application;

import com.agenthub.agent.application.ReceptionSchemeResolver;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.api.dto.WorkbenchConfigResp;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.service.TrialFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作台门面：会话一律按服务端默认接待方案，未就绪时明确失败。
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchChatAppServiceTest {

    @Mock
    private ReceptionSchemeResolver reception;
    @Mock
    private MaTrialSpecLoader maSpecs;
    @Mock
    private TrialSessionAppService sessions;
    @Mock
    private TrialRunAppService runs;
    @Mock
    private TrialAccess access;
    @Mock
    private AgentDefRepository agents;
    @Mock
    private MaDefRepository mas;
    @Mock
    private WorkflowRunSpecLoader workflowSpecs;
    @Mock
    private WorkbenchRouteForceRegistry routeForce;

    private WorkbenchChatAppService service;

    @BeforeEach
    void setUp() {
        // 工作流装载默认给空表：本类多数用例只关心 Agent / MA 线路，避免每处都桩一遍
        lenient().when(workflowSpecs.load(any(), anyLong())).thenReturn(java.util.Map.of());
        service = new WorkbenchChatAppService(reception, maSpecs, sessions, runs, access, agents, mas,
                workflowSpecs, routeForce);
    }

    /** config 返回默认方案、就绪标记与带名称的有序候选。 */
    @Test
    void configExposesDefaultSchemeAndCandidates() {
        MaDef def = def(42L);
        MaCandidate candidate = candidate(7L, 100L, 1);
        when(reception.snapshot()).thenReturn(
                new ReceptionSchemeResolver.Snapshot(def, List.of(candidate), true, null, 5L));
        when(agents.findByIds(List.of(100L))).thenReturn(List.of(agent(100L, "接待助手")));

        WorkbenchConfigResp resp = service.config();

        assertTrue(resp.isReady());
        assertEquals(42L, resp.getSchemeId());
        assertEquals("reception_default", resp.getSchemeCode());
        assertEquals(1, resp.getCandidates().size());
        assertEquals("接待助手", resp.getCandidates().get(0).getAgentName());
        assertEquals(1, resp.getCandidates().get(0).getSortNo());
    }

    /** 未配置默认接待时 config 仍可看，但 ready=false 并给出原因。 */
    @Test
    void configReportsUnreadyWithoutDefault() {
        when(reception.snapshot()).thenReturn(
                new ReceptionSchemeResolver.Snapshot(null, List.of(), false, "尚未设置默认接待方案", null));

        WorkbenchConfigResp resp = service.config();

        assertFalse(resp.isReady());
        assertEquals("尚未设置默认接待方案", resp.getReason());
        assertTrue(resp.getCandidates().isEmpty());
        assertEquals(null, resp.getSchemeId());
    }

    /** 未就绪时禁止建会话，且不自动挑别的方案。 */
    @Test
    void createRejectsWhenUnready() {
        when(reception.snapshot()).thenReturn(
                new ReceptionSchemeResolver.Snapshot(null, List.of(), false, "尚未设置默认接待方案", null));

        BizException ex = assertThrows(BizException.class, service::createSession);

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("默认接待"));
        verify(sessions, never()).createForWorkbench(any());
    }

    /** 就绪时用服务端解析出的方案建 WORKBENCH 会话，忽略前端传入值。 */
    @Test
    void createUsesServerDefaultScheme() {
        MaDef def = def(42L);
        MaRunSpec spec = spec(42L);
        when(reception.snapshot()).thenReturn(
                new ReceptionSchemeResolver.Snapshot(def, List.of(candidate(7L, 100L, 1)), true, null, 5L));
        when(maSpecs.requireForWorkbench(42L)).thenReturn(spec);
        SessionResp created = new SessionResp();
        created.setId(9L);
        created.setEntryChannel("WORKBENCH");
        when(sessions.createForWorkbench(spec)).thenReturn(created);

        SessionResp resp = service.createSession();

        assertEquals(9L, resp.getId());
        assertEquals("WORKBENCH", resp.getEntryChannel());
    }

    /** 方案在就绪判定后被停用：把 TrialFailure 转成可展示的 400，不冒泡成 500。 */
    @Test
    void createMapsSpecFailureToBadRequest() {
        when(reception.snapshot()).thenReturn(
                new ReceptionSchemeResolver.Snapshot(def(42L), List.of(candidate(7L, 100L, 1)), true, null, 5L));
        when(maSpecs.requireForWorkbench(42L)).thenThrow(new TrialFailure("编排方案已停用"));

        BizException ex = assertThrows(BizException.class, service::createSession);

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("编排方案已停用", ex.getMessage());
    }

    /** 列表只委托工作台渠道，不在门面里另写查询。 */
    @Test
    void pageDelegatesWorkbenchChannel() {
        PageResult<SessionResp> page = PageResult.of(0, 1, 20, List.of());
        when(sessions.pageWorkbench(1, 20)).thenReturn(page);

        assertEquals(0, service.pageSessions(1, 20).getTotal());
    }

    /** 调试会话不能被工作台接口读写：渠道校验先于会话服务。 */
    @Test
    void getRejectsDebugSession() {
        when(access.requireWorkbench(11L))
                .thenThrow(new BizException(ErrorCode.FORBIDDEN, "该会话不属于对话入口"));

        BizException ex = assertThrows(BizException.class, () -> service.get(11L));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(sessions, never()).get(anyLong());
    }

    /** 发消息前的渠道校验返回会话 id，控制器据此调用既有运行服务。 */
    @Test
    void requireSessionReturnsCheckedId() {
        RunSession session = new RunSession();
        session.setId(11L);
        when(access.requireWorkbench(11L)).thenReturn(session);

        assertEquals(11L, service.requireSession(11L));
    }

    /** 子计划待批：先校验工作台渠道，再委托既有运行服务；不另写一套查询。 */
    @Test
    void pendingChildPlanChecksChannelAndDelegates() {
        RunSession session = new RunSession();
        session.setId(11L);
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(runs.pendingChildPlan(11L)).thenReturn(java.util.Optional.empty());

        assertTrue(service.pendingChildPlan(11L).isEmpty());
        verify(runs).pendingChildPlan(11L);
    }

    /** 调试会话不能从工作台读子计划待批：渠道校验先于运行服务。 */
    @Test
    void pendingChildPlanRejectsDebugSession() {
        when(access.requireWorkbench(11L))
                .thenThrow(new BizException(ErrorCode.FORBIDDEN, "该会话不属于对话入口"));

        BizException ex = assertThrows(BizException.class, () -> service.pendingChildPlan(11L));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(runs, never()).pendingChildPlan(anyLong());
    }

    /** 发消息前的就绪校验：默认接待停用后已建会话也不能继续发。 */
    @Test
    void sendGateRejectsWhenReceptionDisabled() {
        when(reception.snapshot()).thenReturn(
                new ReceptionSchemeResolver.Snapshot(null, List.of(), false, "默认接待方案已停用", null));

        BizException ex = assertThrows(BizException.class, service::assertReceptionReady);

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("默认接待方案已停用", ex.getMessage());
    }

    /** 就绪时放行，不额外查会话。 */
    @Test
    void sendGatePassesWhenReady() {
        when(reception.snapshot()).thenReturn(
                new ReceptionSchemeResolver.Snapshot(def(42L), List.of(candidate(7L, 100L, 1)), true, null, 5L));

        service.assertReceptionReady();

        verify(sessions, never()).createForWorkbench(any());
    }

    /** 换线：写沿用线路 + 置一次性强制标记，返回更新后的会话。 */
    @Test
    void changeRouteWritesStickyAndArmsForce() {
        RunSession session = workbenchSession();
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(routeSpec(57L));
        when(agents.findById(102L)).thenReturn(java.util.Optional.of(routeAgent(102L, "订单助手", 1)));
        SessionResp updated = new SessionResp();
        updated.setId(11L);
        updated.setStickyRouteKey("order");
        when(sessions.bindStickyRoute(11L, "order", 102L)).thenReturn(updated);

        SessionResp resp = service.changeRoute(11L, "order");

        assertEquals("order", resp.getStickyRouteKey());
        verify(routeForce).put(11L, "order");
        verify(sessions).bindStickyRoute(11L, "order", 102L);
    }

    /** 换线目标已停用：400 明确文案，不改已有线路、不置强制标记。 */
    @Test
    void changeRouteRejectsDisabledTargetWithoutTouchingSticky() {
        RunSession session = workbenchSession();
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(routeSpec(57L));
        when(agents.findById(102L)).thenReturn(java.util.Optional.of(routeAgent(102L, "订单助手", 0)));

        BizException ex = assertThrows(BizException.class, () -> service.changeRoute(11L, "order"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已停用"), ex.getMessage());
        verify(sessions, never()).bindStickyRoute(anyLong(), any(), any());
        verify(routeForce, never()).put(anyLong(), any());
    }

    /** 换线目标不属于本会话方案：按「已停用或不属于」拒绝，不泄露别的方案候选。 */
    @Test
    void changeRouteRejectsKeyOutsideSessionScheme() {
        RunSession session = workbenchSession();
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(routeSpec(57L));

        BizException ex = assertThrows(BizException.class, () -> service.changeRoute(11L, "other_scheme_key"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不属于"), ex.getMessage());
        verify(sessions, never()).bindStickyRoute(anyLong(), any(), any());
    }

    /** 线路列表只列启用中的候选，并把当前沿用键翻译成展示名（chip 不暴露 route_key）。 */
    @Test
    void routesHideDisabledCandidatesAndResolveCurrentName() {
        RunSession session = workbenchSession();
        session.setStickyRouteKey("after_sale");
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(routeSpec(57L));
        when(agents.findByIds(any())).thenReturn(List.of(
                routeAgent(101L, "售后助手", 1), routeAgent(102L, "订单助手", 0)));

        com.agenthub.runtime.api.dto.WorkbenchRouteOptionsResp resp = service.routes(11L);

        assertEquals("after_sale", resp.getCurrentRouteKey());
        assertEquals("售后助手", resp.getCurrentRouteName());
        assertEquals(1, resp.getCandidates().size());
        assertEquals("after_sale", resp.getCandidates().get(0).getRouteKey());
    }

    /** 已停用线路：当前名称为空，前端据此显示「线路已失效」。 */
    @Test
    void routesReportMissingNameForInvalidSticky() {
        RunSession session = workbenchSession();
        session.setStickyRouteKey("gone_route");
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(routeSpec(57L));
        when(agents.findByIds(any())).thenReturn(List.of(
                routeAgent(101L, "售后助手", 1), routeAgent(102L, "订单助手", 1)));

        com.agenthub.runtime.api.dto.WorkbenchRouteOptionsResp resp = service.routes(11L);

        assertEquals("gone_route", resp.getCurrentRouteKey());
        assertNull(resp.getCurrentRouteName());
        assertEquals(2, resp.getCandidates().size());
    }

    /** 刀5：config 里 MA 候选的展示名与说明取自目标方案，不读候选上的 intent_desc。 */
    @Test
    void configExposesMaCandidateWithSchemeNameAndDescription() {
        MaCandidate maCandidate = maCandidate(9L, 77L, 1);
        maCandidate.setIntentDesc("候选上的旧文案");
        when(reception.snapshot()).thenReturn(new ReceptionSchemeResolver.Snapshot(
                def(42L), List.of(maCandidate), true, null, 5L));
        when(mas.findByIds(List.of(77L))).thenReturn(List.of(targetDef(77L, "售后编排", "售后问题统一编排")));

        WorkbenchConfigResp resp = service.config();

        assertEquals(1, resp.getCandidates().size());
        assertEquals("MA", resp.getCandidates().get(0).getTargetType());
        assertEquals(77L, resp.getCandidates().get(0).getMaDefId());
        assertEquals("售后编排", resp.getCandidates().get(0).getAgentName());
        // 说明只认方案自身，候选列上的历史值不再参与展示
        assertEquals("售后问题统一编排", resp.getCandidates().get(0).getIntentDesc());
    }

    /** 刀5：换线下拉把 MA 目标算作一条可选线路，chip 显示方案名。 */
    @Test
    void routesExposeMaTargetAndResolveChipName() {
        RunSession session = workbenchSession();
        session.setStickyRouteKey("ma_after_sale");
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(maSpec(57L, true));

        com.agenthub.runtime.api.dto.WorkbenchRouteOptionsResp resp = service.routes(11L);

        assertEquals(1, resp.getCandidates().size());
        assertEquals("MA", resp.getCandidates().get(0).getTargetType());
        assertEquals("售后编排", resp.getCandidates().get(0).getAgentName());
        assertEquals("ma_after_sale", resp.getCurrentRouteKey());
        assertEquals("售后编排", resp.getCurrentRouteName());
    }

    /** 刀5：换线到 MA 只写 route_key，sticky_agent_id 留空。 */
    @Test
    void changeRouteToMaTargetBindsStickyWithoutAgentId() {
        RunSession session = workbenchSession();
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(maSpec(57L, true));
        SessionResp updated = new SessionResp();
        updated.setId(11L);
        updated.setStickyRouteKey("ma_after_sale");
        when(sessions.bindStickyRoute(11L, "ma_after_sale", null)).thenReturn(updated);

        SessionResp resp = service.changeRoute(11L, "ma_after_sale");

        assertEquals("ma_after_sale", resp.getStickyRouteKey());
        verify(routeForce).put(11L, "ma_after_sale");
        verify(sessions).bindStickyRoute(11L, "ma_after_sale", null);
    }

    /** 刀5：目标方案被移出池 / 停用（装载失败）时换线 400，且不改 sticky。 */
    @Test
    void changeRouteRejectsUnloadedMaTarget() {
        RunSession session = workbenchSession();
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(maSpec(57L, false));

        BizException ex = assertThrows(BizException.class, () -> service.changeRoute(11L, "ma_after_sale"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已停用"), ex.getMessage());
        verify(sessions, never()).bindStickyRoute(anyLong(), any(), any());
        verify(routeForce, never()).put(anyLong(), any());
    }

    /** 刀5B：换线下拉把工作流目标算作一条可选线路，chip 显示工作流名而不是内部 key。 */
    @Test
    void routesExposeWorkflowTargetAndResolveChipName() {
        RunSession session = workbenchSession();
        session.setStickyRouteKey("wf_reimburse");
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(workflowSpec(57L, true));

        com.agenthub.runtime.api.dto.WorkbenchRouteOptionsResp resp = service.routes(11L);

        assertEquals(1, resp.getCandidates().size());
        assertEquals("WORKFLOW", resp.getCandidates().get(0).getTargetType());
        assertEquals(88L, resp.getCandidates().get(0).getWorkflowDefId());
        assertEquals("报销审批", resp.getCandidates().get(0).getAgentName());
        assertEquals("wf_reimburse", resp.getCurrentRouteKey());
        assertEquals("报销审批", resp.getCurrentRouteName());
    }

    /** 刀5B：换线到工作流只写 route_key，sticky_agent_id 留空。 */
    @Test
    void changeRouteToWorkflowTargetBindsStickyWithoutAgentId() {
        RunSession session = workbenchSession();
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(workflowSpec(57L, true));
        SessionResp updated = new SessionResp();
        updated.setId(11L);
        updated.setStickyRouteKey("wf_reimburse");
        when(sessions.bindStickyRoute(11L, "wf_reimburse", null)).thenReturn(updated);

        SessionResp resp = service.changeRoute(11L, "wf_reimburse");

        assertEquals("wf_reimburse", resp.getStickyRouteKey());
        verify(routeForce).put(11L, "wf_reimburse");
        verify(sessions).bindStickyRoute(11L, "wf_reimburse", null);
    }

    /** 刀5B：工作流取消发布 / 被移出池（装载失败）时换线 400，且不改 sticky、不置强制标记。 */
    @Test
    void changeRouteRejectsUnloadedWorkflowTarget() {
        RunSession session = workbenchSession();
        when(access.requireWorkbench(11L)).thenReturn(session);
        when(maSpecs.resolve(any(), any())).thenReturn(workflowSpec(57L, false));

        BizException ex = assertThrows(BizException.class, () -> service.changeRoute(11L, "wf_reimburse"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未发布"), ex.getMessage());
        verify(sessions, never()).bindStickyRoute(anyLong(), any(), any());
        verify(routeForce, never()).put(anyLong(), any());
    }

    /** 刀5B：config 里工作流候选的名称与说明取自 workflow_def，不读候选上的 intent_desc。 */
    @Test
    void configExposesWorkflowCandidateWithDefinitionDescription() {
        MaCandidate workflowCandidate = workflowCandidate(9L, 88L, 1);
        workflowCandidate.setIntentDesc("候选上的旧文案");
        when(reception.snapshot()).thenReturn(new ReceptionSchemeResolver.Snapshot(
                def(42L), List.of(workflowCandidate), true, null, 5L));
        // config 视图没有会话快照，走装载器取工作流名称 / 说明
        when(workflowSpecs.load(any(), anyLong())).thenReturn(java.util.Map.of(88L,
                new com.agenthub.runtime.domain.model.WorkflowRunSpec(88L, "reimburse", "报销审批",
                        "处理报销申请与审批流转", 900L)));

        WorkbenchConfigResp resp = service.config();

        assertEquals(1, resp.getCandidates().size());
        assertEquals("WORKFLOW", resp.getCandidates().get(0).getTargetType());
        assertEquals(88L, resp.getCandidates().get(0).getWorkflowDefId());
        assertEquals("报销审批", resp.getCandidates().get(0).getAgentName());
        assertEquals("处理报销申请与审批流转", resp.getCandidates().get(0).getIntentDesc());
    }

    private static RunSession workbenchSession() {
        RunSession session = new RunSession();
        session.setId(11L);
        session.setUserId(100L);
        session.setMaDefId(57L);
        session.setEntryChannel("WORKBENCH");
        return session;
    }

    /** 两条候选（售后 / 订单）的方案来源，供换线测试复用。 */
    private static MaRunSpec routeSpec(Long maId) {
        MaCandidate afterSale = new MaCandidate();
        afterSale.setId(1L);
        afterSale.setMaId(maId);
        afterSale.setAgentId(101L);
        afterSale.setRouteKey("after_sale");
        afterSale.setIntentDesc("退换货");
        afterSale.setSortNo(1);
        afterSale.setEnabled(1);
        MaCandidate order = new MaCandidate();
        order.setId(2L);
        order.setMaId(maId);
        order.setAgentId(102L);
        order.setRouteKey("order");
        order.setIntentDesc("订单查询");
        order.setSortNo(2);
        order.setEnabled(1);
        return new MaRunSpec(maId, "reception_default", "默认接待", "ROUTER_PARALLEL",
                new AgentRuntimeConfig(), List.of(afterSale, order), null, null);
    }

    private static AgentDef routeAgent(Long id, String name, int status) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode("child_" + id);
        agent.setStatus(status);
        agent.setAllowOrchestrated(1);
        return agent;
    }

    private static MaDef def(Long id) {
        MaDef def = new MaDef();
        def.setId(id);
        def.setTenantId(1L);
        def.setCode("reception_default");
        def.setName("默认接待");
        def.setTemplate("ROUTER_PARALLEL");
        def.setEnabled(1);
        return def;
    }

    private static MaCandidate candidate(Long id, Long agentId, int sortNo) {
        MaCandidate candidate = new MaCandidate();
        candidate.setId(id);
        candidate.setMaId(42L);
        candidate.setAgentId(agentId);
        candidate.setRouteKey("reception_assistant");
        candidate.setIntentDesc("通用接待");
        candidate.setSortNo(sortNo);
        candidate.setEnabled(1);
        return candidate;
    }

    private static AgentDef agent(Long id, String name) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        return agent;
    }

    private static MaRunSpec spec(Long maId) {
        return new MaRunSpec(maId, "reception_default", "默认接待", "ROUTER_PARALLEL",
                new AgentRuntimeConfig(), List.of(), null, null);
    }

    /** 刀5：目标方案候选（无子 Agent，只有目标 ma_def 主键）。 */
    private static MaCandidate maCandidate(Long id, Long targetMaId, int sortNo) {
        MaCandidate candidate = new MaCandidate();
        candidate.setId(id);
        candidate.setMaId(57L);
        candidate.setTargetType(MaCandidate.TARGET_MA);
        candidate.setMaDefId(targetMaId);
        candidate.setRouteKey("ma_after_sale");
        candidate.setSortNo(sortNo);
        candidate.setEnabled(1);
        return candidate;
    }

    /** 刀5：默认接待方案 + 一条 MA 候选；withTarget=false 模拟目标已停用 / 被移出池（装载失败）。 */
    private static MaRunSpec maSpec(Long maId, boolean withTarget) {
        MaCandidate ma = maCandidate(9L, 77L, 1);
        java.util.Map<Long, MaRunSpec> targets = withTarget
                ? java.util.Map.of(77L, new MaRunSpec(77L, "after_sale", "售后编排", "售后问题统一编排",
                "ROUTER_PARALLEL", new AgentRuntimeConfig(), List.of(), null, null, false))
                : java.util.Map.of();
        return new MaRunSpec(maId, "reception_default", "默认接待", "默认接待", "ROUTER_PARALLEL",
                new AgentRuntimeConfig(), List.of(ma), null, null, true, targets, java.util.Map.of());
    }

    private static MaDef targetDef(Long id, String name, String description) {
        MaDef def = new MaDef();
        def.setId(id);
        def.setTenantId(1L);
        def.setCode("after_sale");
        def.setName(name);
        def.setDescription(description);
        def.setTemplate("ROUTER_PARALLEL");
        def.setEnabled(1);
        return def;
    }

    /** 刀5B：工作流候选（无子 Agent，只有目标 workflow_def 主键）。 */
    private static MaCandidate workflowCandidate(Long id, Long workflowDefId, int sortNo) {
        MaCandidate candidate = new MaCandidate();
        candidate.setId(id);
        candidate.setMaId(57L);
        candidate.setTargetType(MaCandidate.TARGET_WORKFLOW);
        candidate.setWorkflowDefId(workflowDefId);
        candidate.setRouteKey("wf_reimburse");
        candidate.setSortNo(sortNo);
        candidate.setEnabled(1);
        return candidate;
    }

    /** 刀5B：默认接待方案 + 一条工作流候选；withTarget=false 模拟取消发布 / 被移出池（装载失败）。 */
    private static MaRunSpec workflowSpec(Long maId, boolean withTarget) {
        MaCandidate workflow = workflowCandidate(9L, 88L, 1);
        java.util.Map<Long, com.agenthub.runtime.domain.model.WorkflowRunSpec> targets = withTarget
                ? java.util.Map.of(88L, new com.agenthub.runtime.domain.model.WorkflowRunSpec(
                88L, "reimburse", "报销审批", "处理报销申请与审批流转", 900L))
                : java.util.Map.of();
        return new MaRunSpec(maId, "reception_default", "默认接待", "默认接待", "ROUTER_PARALLEL",
                new AgentRuntimeConfig(), List.of(workflow), null, null, true, java.util.Map.of(), targets);
    }
}

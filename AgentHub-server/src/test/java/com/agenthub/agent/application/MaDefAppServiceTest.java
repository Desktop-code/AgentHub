package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.AgentAvatarConfigDto;
import com.agenthub.agent.api.dto.MaCandidateReq;
import com.agenthub.agent.api.dto.MaDefPageReq;
import com.agenthub.agent.api.dto.MaDefRowResp;
import com.agenthub.agent.api.dto.MaDefSaveReq;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.agent.domain.service.MaTenantProvider;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多智能体方案用例窄测。所属域：agent 的 application。
 *
 * <p>本刀最关键的四条验收：
 * 1）方案以 ma_def 自己为根：请求里遗留的 rootAgentId 一律忽略并落 null，只写 ma_def + ma_candidate；
 * 2）普通方案（历史根值为空或非空）都能保存，不要求先清根；
 * 3）不再按根查「同根唯一启用」，历史绑根行照样能保存；
 * 4）删 / 启停方案都不会反向写 Agent 根配置；
 * 5）方案形象落在 {@code ma_def.avatar_config}，保存 / 回显都不经过 Agent 根。
 */
class MaDefAppServiceTest {

    private static final long TENANT = 1L;
    private static final long ROOT_ID = 5L;

    private MaDefRepository mas;
    private AgentDefRepository agents;
    private MaDefAppService service;
    private final AtomicReference<MaDef> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        mas = mock(MaDefRepository.class);
        agents = mock(AgentDefRepository.class);
        MaTenantProvider tenant = mock(MaTenantProvider.class);
        when(tenant.currentTenantId()).thenReturn(TENANT);
        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(1L);
        service = new MaDefAppService(mas, agents, tenant, operator, mock(OperLogWriter.class),
                mock(WorkflowDefRepository.class), mock(ModelInstanceRepository.class),
                mock(AgentPromptAppService.class));

        when(mas.findByTenantAndCode(eq(TENANT), any())).thenReturn(Optional.empty());
        when(mas.countEnabledCandidates(any())).thenReturn(Map.of());
        when(mas.insert(any(), anyLong())).thenAnswer(invocation -> {
            MaDef def = invocation.getArgument(0);
            def.setId(42L);
            stored.set(def);
            return 42L;
        });
        when(mas.findById(42L)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));

        AgentDef root = router();
        AgentDef child = leaf();
        when(agents.findById(ROOT_ID)).thenReturn(Optional.of(root));
        when(agents.findById(99L)).thenReturn(Optional.of(child));
        when(agents.findByIds(any())).thenReturn(List.of());
    }

    /**
     * 本刀核心：保存时请求里遗留的根值被忽略，只落 ma 侧且根恒为 null。
     * 若哪天有人重新读回 rootAgentId，这里的断言会立刻变红。
     */
    @Test
    void legacyRootInRequestIsIgnoredAndOnlyMaIsWritten() {
        MaDefSaveReq req = request(ROOT_ID, candidate(99L));

        MaDefRowResp row = service.create(req);

        // 方案以 ma_def 自己为根：历史绑根字段既不读也不写
        assertNull(row.getRootAgentId());
        assertNull(row.getRootAgentName());
        assertEquals("ROUTER_PARALLEL", row.getTemplate());
        assertEquals(1, row.getCandidateEnabledCount());
        // 唯一真源在 ma：不得再写旧根 Agent 的 runtime_config_json
        verify(agents, never()).updateRuntimeConfig(anyLong(), any(), anyLong());
        // 候选只落 ma_candidate
        verify(mas).replaceCandidates(eq(42L), any());
    }

    @Test
    void schemeIsSelfRootedAndKeepsNullRoot() {
        MaDefSaveReq req = request(null, candidate(99L));

        MaDefRowResp row = service.create(req);

        assertNull(row.getRootAgentId());
        assertNull(row.getRootAgentName());
        // 方案自己就是根：没有 Agent 根也能直接保存候选
        assertEquals(1, row.getCandidateEnabledCount());
        verify(agents, never()).updateRuntimeConfig(anyLong(), any(), anyLong());
    }

    /**
     * 方案形象落在方案自己身上：写 ma_def.avatar_config，读回来还是同一份；
     * 未配置时为 null，前端按方案名走 8 套机器人兜底。
     */
    @Test
    void schemeAvatarIsStoredOnMaDefAndEchoedBack() {
        MaDefSaveReq req = request(null, candidate(99L));
        AgentAvatarConfigDto avatar = new AgentAvatarConfigDto();
        avatar.setType("preset");
        avatar.setIcon("look-coder");
        avatar.setColor("#635BFF");
        req.setAvatarConfig(avatar);

        MaDefRowResp row = service.create(req);

        // 落库的是方案自己的列，不是任何 Agent 的 avatar_config
        assertNotNull(stored.get().getAvatarConfigJson());
        assertTrue(stored.get().getAvatarConfigJson().contains("\"look-coder\""));
        assertNotNull(row.getAvatarConfig());
        assertEquals("preset", row.getAvatarConfig().getType());
        assertEquals("look-coder", row.getAvatarConfig().getIcon());
    }

    /** 更新路径同样把形象写回方案自己；清空形象落 null，不回落 Agent。 */
    @Test
    void updateWritesSchemeOwnAvatarAndClearsIt() {
        MaDef existing = new MaDef();
        existing.setId(42L);
        existing.setTenantId(TENANT);
        existing.setCode("plan_a");
        existing.setName("方案 A");
        existing.setTemplate("ROUTER_PARALLEL");
        existing.setEnabled(AgentStatus.ENABLED);
        existing.setConfigJson("{\"maTemplate\":\"ROUTER_PARALLEL\"}");
        stored.set(existing);
        when(mas.listCandidates(42L)).thenReturn(List.<MaCandidate>of());

        MaDefSaveReq req = request(null, candidate(99L));
        AgentAvatarConfigDto avatar = new AgentAvatarConfigDto();
        avatar.setType("preset");
        avatar.setIcon("look-scholar");
        avatar.setColor("#635BFF");
        req.setAvatarConfig(avatar);
        service.update(42L, req);
        assertTrue(stored.get().getAvatarConfigJson().contains("\"look-scholar\""));

        // 用户清空形象：落 null，由前端按方案名兜底，不回头借 Agent 根形象
        MaDefSaveReq clear = request(null, candidate(99L));
        clear.setAvatarConfig(null);
        service.update(42L, clear);
        assertNull(stored.get().getAvatarConfigJson());
    }

    /** 更新路径同样只写 ma；否则方案页改候选会顺手把根配置洗掉。 */
    @Test
    void updateLeavesRouterConfigUntouched() {
        MaDef existing = new MaDef();
        existing.setId(42L);
        existing.setTenantId(TENANT);
        existing.setCode("plan_a");
        existing.setName("方案 A");
        existing.setTemplate("ROUTER_PARALLEL");
        existing.setEnabled(AgentStatus.ENABLED);
        existing.setRootAgentId(ROOT_ID);
        existing.setConfigJson("{\"maTemplate\":\"ROUTER_PARALLEL\"}");
        stored.set(existing);
        when(mas.listCandidates(42L)).thenReturn(List.<MaCandidate>of());

        MaDefSaveReq req = request(ROOT_ID, candidate(99L));
        service.update(42L, req);

        verify(mas).replaceCandidates(eq(42L), any());
        verify(agents, never()).updateRuntimeConfig(anyLong(), any(), anyLong());
        // 历史根值在保存时被清空，不再作为运行语义延续
        assertNull(stored.get().getRootAgentId());
    }

    /** 启用方案也不得顺手重写旧根配置（停止双写后执行侧直接读 ma）。 */
    @Test
    void enableLeavesRouterConfigUntouched() {
        MaDef existing = new MaDef();
        existing.setId(42L);
        existing.setTenantId(TENANT);
        existing.setCode("plan_a");
        existing.setName("方案 A");
        existing.setEnabled(AgentStatus.DISABLED);
        existing.setRootAgentId(ROOT_ID);
        stored.set(existing);
        when(mas.updateEnabled(42L, TENANT, AgentStatus.ENABLED, 1L)).thenReturn(1);
        when(mas.listCandidates(42L)).thenReturn(List.<MaCandidate>of());

        service.setEnabled(42L, AgentStatus.ENABLED);

        verify(mas).updateEnabled(42L, TENANT, AgentStatus.ENABLED, 1L);
        verify(agents, never()).updateRuntimeConfig(anyLong(), any(), anyLong());
    }

    /**
     * 历史绑根行不再受「同根唯一启用」约束：不查根、不要求用户先清根，照样能保存。
     */
    @Test
    void historicalRootValueDoesNotBlockSave() {
        MaDef existing = new MaDef();
        existing.setId(7L);
        existing.setTenantId(TENANT);
        existing.setCode("existing");
        existing.setName("已有方案");
        existing.setEnabled(AgentStatus.ENABLED);
        existing.setRootAgentId(ROOT_ID);
        existing.setConfigJson("{\"maTemplate\":\"ROUTER_PARALLEL\"}");
        stored.set(existing);
        when(mas.findById(7L)).thenReturn(Optional.of(existing));
        when(mas.findByTenantAndCode(TENANT, "existing")).thenReturn(Optional.of(existing));
        when(mas.listCandidates(7L)).thenReturn(List.<MaCandidate>of());

        MaDefSaveReq req = request(null, candidate(99L));
        req.setCode("existing");
        req.setName("第二条启用");

        service.update(7L, req);

        // 不再按根查重；保存成功并把历史根值清空
        verify(mas, never()).findEnabledByRoot(anyLong(), anyLong(), any());
        verify(mas).update(any(), anyLong());
        assertNull(existing.getRootAgentId());
    }

    @Test
    void routerChildIsRejectedWithChineseMessage() {
        AgentDef childRouter = router();
        childRouter.setId(99L);
        when(agents.findById(99L)).thenReturn(Optional.of(childRouter));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(request(ROOT_ID, candidate(99L))));
        assertTrue(ex.getMessage().contains("子 Agent 不能是 ROUTER"), ex.getMessage());
        verify(mas, never()).insert(any(), anyLong());
    }

    /**
     * 结构钉死：方案用例不得再持有路由仓储，从依赖上堵死「保存方案顺手写 agent_route」。
     * 比行为断言更硬——想恢复双写必须先改构造器，改不动就是编译期就能看见。
     */
    @Test
    void maServiceHasNoRouteRepositoryDependency() {
        boolean hasRouteDependency = Arrays.stream(MaDefAppService.class.getDeclaredFields())
                .anyMatch(field -> AgentRouteRepository.class.isAssignableFrom(field.getType()));
        assertFalse(hasRouteDependency, "MaDefAppService 不应再依赖 AgentRouteRepository");
    }

    /** 刀3 已删除双写服务；有人加回来时这条会立刻失败，提醒先对齐产品口径。 */
    @Test
    void dualWriteSyncServiceIsRemoved() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.agenthub.agent.application.MaDefSyncService"));
    }

    /**
     * 刀4：候选意图说明只来自 Agent 简介。
     *
     * <p>旧前端会把只读栏的文案原样回传（这里等于简介），落库的 {@code intent_desc}
     * 仍必须是该 Agent 的简介；这样旧代码读列时与新版判路由看到的是同一份口径。
     */
    @Test
    void candidateIntentDescIsBackfilledFromAgentDescription() {
        MaDefSaveReq req = request(ROOT_ID, candidate(99L));

        service.create(req);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<MaCandidate>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mas).replaceCandidates(eq(42L), captor.capture());
        assertEquals("查询订单、物流与退款进度", captor.getValue().get(0).getIntentDesc());
    }

    /**
     * 刀4：想借方案单独改意图说明 → 明确 400，而不是静默忽略。
     *
     * <p>否则运营会以为改成功了，下一次判路由读到的却还是 Agent 简介，两边对不上。
     */
    @Test
    void overridingIntentDescInSchemeIsRejected() {
        MaDefSaveReq req = request(ROOT_ID, candidateWithIntent(99L, "请求里自带的意图说明"));

        BizException ex = assertThrows(BizException.class, () -> service.create(req));

        assertTrue(ex.getMessage().contains("简介"), ex.getMessage());
        verify(mas, never()).insert(any(), anyLong());
    }

    /**
     * 刀4：简介为空的 Agent 不得新绑到 MA 候选。
     *
     * <p>空简介没法判路由，必须在 Agent 管理先补齐；请求里塞 intentDesc 也不能绕过。
     */
    @Test
    void blankDescriptionAgentCannotBeBound() {
        AgentDef noDescription = leaf();
        noDescription.setDescription("   ");
        when(agents.findById(99L)).thenReturn(Optional.of(noDescription));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(request(ROOT_ID, candidate(99L))));

        assertTrue(ex.getMessage().contains("简介"), ex.getMessage());
        verify(mas, never()).insert(any(), anyLong());
    }

    /**
     * 本刀：入口与普通方案同列展示，名称读库内值（已开放编辑）。
     *
     * <p>列表不再把入口名称改写成固定的「Router」；备注仍固定平台文案。
     * 历史库里的名称原样回显，运营可在详情里改名并保存。
     */
    @Test
    void entrySchemeIsListedWithStoredName() {
        MaDef entry = entryDef();
        entry.setName("默认接待");
        entry.setDescription(null);
        when(mas.page(any())).thenReturn(PageResult.of(1, 1, 10, List.of(entry)));
        when(mas.findByIds(any())).thenReturn(List.of());

        MaDefRowResp row = service.page(new MaDefPageReq()).getRecords().get(0);

        assertEquals(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER, row.getTemplate());
        assertEquals("默认接待", row.getName());
        assertEquals(AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION, row.getDescription());
    }

    /** 本刀：入口名称可改并落库；列表 / 详情 / 会话头共用的 displayName 立即读新名。 */
    @Test
    void entryNameCanBeRenamed() {
        MaDef entry = entryDef();
        stored.set(entry);
        when(mas.listCandidates(42L)).thenReturn(List.<MaCandidate>of());

        MaDefSaveReq rename = entryRequest();
        rename.setName("  智能助手  ");

        service.update(42L, rename);

        // 前后空白被 requiredText 收掉；库内保存的是用户提交的新名称
        assertEquals("智能助手", stored.get().getName());
        assertEquals("智能助手", stored.get().displayName());
        assertEquals(AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION, stored.get().getDescription());
        assertEquals(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER, stored.get().getTemplate());
        verify(mas).update(eq(entry), eq(1L));
    }

    /** 本刀：空名称不放行，沿用现网必填校验。 */
    @Test
    void entryNameCannotBeBlank() {
        MaDef entry = entryDef();
        stored.set(entry);
        when(mas.listCandidates(42L)).thenReturn(List.<MaCandidate>of());

        MaDefSaveReq blank = entryRequest();
        blank.setName("   ");

        BizException ex = assertThrows(BizException.class, () -> service.update(42L, blank));
        assertTrue(ex.getMessage().contains("方案名称不能为空"), ex.getMessage());
        verify(mas, never()).update(any(), anyLong());
    }

    /** 本刀：编码 / 备注仍是入口身份字段，有值且不一致时明确拒绝且不落库。 */
    @Test
    void entryCodeAndRemarkRemainLocked() {
        MaDef entry = entryDef();
        stored.set(entry);
        when(mas.listCandidates(42L)).thenReturn(List.<MaCandidate>of());

        MaDefSaveReq remark = entryRequest();
        remark.setDescription("换个备注");
        BizException descEx = assertThrows(BizException.class, () -> service.update(42L, remark));
        assertTrue(descEx.getMessage().contains("备注"), descEx.getMessage());

        MaDefSaveReq recode = entryRequest();
        recode.setCode("another_code");
        BizException codeEx = assertThrows(BizException.class, () -> service.update(42L, recode));
        assertTrue(codeEx.getMessage().contains("编码固定"), codeEx.getMessage());

        verify(mas, never()).update(any(), anyLong());
    }

    /** 补丁：入口只改模型 / 提示词时，名称原样回传即可保存；备注仍收敛到平台锁定值。 */
    @Test
    void entryCanBeSavedWhenIdentityFieldsAreUnchanged() {
        MaDef entry = entryDef();
        stored.set(entry);
        when(mas.listCandidates(42L)).thenReturn(List.<MaCandidate>of());

        service.update(42L, entryRequest());

        assertEquals("Router", stored.get().getName());
        assertEquals(AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION, stored.get().getDescription());
        assertEquals(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER, stored.get().getTemplate());
        verify(mas).update(eq(entry), eq(1L));
    }

    /**
     * 本刀：入口路由池在入口画布里维护，保存前必须与运行侧口径一致去重。
     *
     * <p>裸专家 A 与「成员已包含 A」的入池方案同时存在时，只保留方案、去掉裸专家；
     * 两套方案之间成员重叠不合并、不删除（交给入口判）。
     */
    @Test
    void entrySavePrunesBareExpertCoveredByScheme() {
        MaDef entry = entryDef();
        stored.set(entry);

        // 已入池的普通方案 77：成员就是专家 99
        MaDef scheme = new MaDef();
        scheme.setId(77L);
        scheme.setTenantId(TENANT);
        scheme.setCode("order_ops");
        scheme.setName("订单场景");
        scheme.setDescription("订单、物流、退款一条链");
        scheme.setTemplate("ROUTER_PARALLEL");
        scheme.setEnabled(AgentStatus.ENABLED);
        when(mas.findById(77L)).thenReturn(Optional.of(scheme));
        when(mas.listCandidates(77L)).thenReturn(List.of(stored(99L)));
        when(mas.listCandidates(42L)).thenReturn(List.<MaCandidate>of());

        MaDefSaveReq req = entryRequest();
        MaCandidateReq schemeTarget = new MaCandidateReq();
        schemeTarget.setTargetType(MaCandidateReq.TARGET_MA);
        schemeTarget.setMaDefId(77L);
        schemeTarget.setEnabled(1);
        req.setCandidates(List.of(schemeTarget, candidate(99L)));

        service.update(42L, req);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<MaCandidate>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mas).replaceCandidates(eq(42L), captor.capture());
        List<MaCandidate> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertTrue(saved.get(0).isMaTarget());
        assertEquals(77L, saved.get(0).getMaDefId());
    }

    /** 只入池裸专家、没有任何方案覆盖它时，名单必须原样保留。 */
    @Test
    void entrySaveKeepsBareExpertWhenNoSchemeCoversIt() {
        MaDef entry = entryDef();
        stored.set(entry);
        when(mas.listCandidates(42L)).thenReturn(List.<MaCandidate>of());

        MaDefSaveReq req = entryRequest();
        req.setCandidates(List.of(candidate(99L)));

        service.update(42L, req);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<MaCandidate>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mas).replaceCandidates(eq(42L), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(99L, captor.getValue().get(0).getAgentId());
    }

    /** 方案 77 的成员候选（用于去重判定的「已被方案覆盖」）。 */
    private static MaCandidate stored(Long agentId) {
        MaCandidate member = new MaCandidate();
        member.setMaId(77L);
        member.setAgentId(agentId);
        return member;
    }

    /** 入口方案库内行：虚拟根、入口类型、历史名称（模拟 SQL 迁移前的「默认接待」）。 */
    private static MaDef entryDef() {        MaDef def = new MaDef();
        def.setId(42L);
        def.setTenantId(TENANT);
        def.setCode("reception_default");
        def.setName("默认接待");
        def.setTemplate(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER);
        def.setEnabled(AgentStatus.ENABLED);
        def.setIsReceptionDefault(1);
        def.setConfigJson("{\"maTemplate\":\"ENTRY_ROUTER\"}");
        return def;
    }

    /**
     * 入口保存请求：名称原样回传（已可改），备注 / 编码仍按锁定值回传；
     * 只表达能力之外的模型 / 提示词由 config 承载。
     * 不带候选：入口的路由池在默认接待页维护，本抽屉保存时原样透传。
     */
    private static MaDefSaveReq entryRequest() {
        MaDefSaveReq req = new MaDefSaveReq();
        req.setCode("reception_default");
        req.setName("Router");
        req.setDescription(AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION);
        req.setTemplate(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER);
        req.setRootAgentId(null);
        req.setEnabled(1);
        req.setCandidates(List.of());
        return req;
    }

    /** 新建请求：默认路由并行 + 一条候选。 */
    private static MaDefSaveReq request(Long rootAgentId, MaCandidateReq candidate) {
        MaDefSaveReq req = new MaDefSaveReq();
        req.setCode("plan_a");
        req.setName("方案 A");
        req.setTemplate("ROUTER_PARALLEL");
        req.setRootAgentId(rootAgentId);
        req.setEnabled(1);
        req.setCandidates(List.of(candidate));
        return req;
    }

    /**
     * 候选请求；route_key 为空走服务端按编码生成。
     * <p>刀4 起默认不带 intentDesc：意图说明只由服务端按 Agent 简介派生。
     */
    private static MaCandidateReq candidate(Long agentId) {
        MaCandidateReq req = new MaCandidateReq();
        req.setAgentId(agentId);
        req.setEnabled(1);
        return req;
    }

    /** 候选请求（显式带上 intentDesc）：只用于验证「想单独改文案会被拒」。 */
    private static MaCandidateReq candidateWithIntent(Long agentId, String intent) {
        MaCandidateReq req = candidate(agentId);
        req.setIntentDesc(intent);
        return req;
    }

    /** 启用中的 ROUTER，只允许 REACT 子。 */
    private static AgentDef router() {
        AgentDef root = new AgentDef();
        root.setId(ROOT_ID);
        root.setName("总控");
        root.setCode("root");
        root.setRunMode(AgentRunMode.ROUTER.name());
        root.setStatus(AgentStatus.ENABLED);
        root.setRuntimeConfigJson("{\"maChildRunModesAllowed\":[\"REACT\"]}");
        return root;
    }

    /** 启用、允许被编排的 REACT 叶子；带简介（刀4 起简介是意图说明的唯一来源）。 */
    private static AgentDef leaf() {
        AgentDef child = new AgentDef();
        child.setId(99L);
        child.setName("订单专家");
        child.setCode("order");
        child.setDescription("查询订单、物流与退款进度");
        child.setRunMode(AgentRunMode.REACT.name());
        child.setStatus(AgentStatus.ENABLED);
        child.setAllowOrchestrated(1);
        return child;
    }
}

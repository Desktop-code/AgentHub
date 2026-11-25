package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多智能体方案保存侧规则窄测。所属域：agent。
 *
 * <p>覆盖本刀最容易回归的硬约束：模板白名单、候选纪律（子 ROUTER / 未允许被编排 /
 * route_key 重复 / 数量硬顶）、目标方案与工作流目标门闩。
 *
 * <p>「绑根必须 ROUTER / 同根唯一」两项已随「方案以 ma_def 自己为根」下线，不再有对应规则。
 */
class MaDefPolicyTest {

    @Test
    void templateCollapsesToTwoModes() {
        assertEquals("ROUTER_PARALLEL", MaDefPolicy.assertTemplate("ROUTER_PARALLEL"));
        assertEquals("ROUTER_THEN_SEQUENCE", MaDefPolicy.assertTemplate("ROUTER_THEN_SEQUENCE"));
        // 历史并行类 / 串行类在保存时收口，配置其它字段不在这一步丢
        assertEquals("ROUTER_PARALLEL", MaDefPolicy.assertTemplate("COMPETE"));
        assertEquals("ROUTER_PARALLEL", MaDefPolicy.assertTemplate("FIXED_PARALLEL"));
        assertEquals("ROUTER_THEN_SEQUENCE", MaDefPolicy.assertTemplate("FIXED_SEQUENCE"));
        BizException ex = assertThrows(BizException.class, () -> MaDefPolicy.assertTemplate("DEBATE"));
        assertTrue(ex.getMessage().contains("意图路由分发"), ex.getMessage());
    }

    /**
     * 本刀：入口名称开放编辑，编码 / 备注仍锁定；改名后展示名立即读库内值。
     */
    @Test
    void entryNameIsEditableWhileCodeAndRemarkStayLocked() {
        MaDef entry = new MaDef();
        entry.setId(42L);
        entry.setTenantId(1L);
        entry.setCode("reception_default");
        entry.setName("Router");
        entry.setDescription(AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION);
        entry.setTemplate(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER);

        // 名称不在校验入参里：改名的请求不会被这条身份校验拦住
        assertDoesNotThrow(() -> MaDefPolicy.assertEntryIdentity(
                entry, "reception_default", AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION));

        BizException codeLocked = assertThrows(BizException.class,
                () -> MaDefPolicy.assertEntryIdentity(
                        entry, "another_code", AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION));
        assertTrue(codeLocked.getMessage().contains("编码固定"), codeLocked.getMessage());

        BizException remarkLocked = assertThrows(BizException.class,
                () -> MaDefPolicy.assertEntryIdentity(entry, "reception_default", "换个备注"));
        assertTrue(remarkLocked.getMessage().contains("备注"), remarkLocked.getMessage());

        // 展示名：改名后回显新名称；空名才退回平台默认
        entry.setName(" 智能助手 ");
        assertEquals("智能助手", entry.displayName());
        entry.setName("   ");
        assertEquals(AgentRuntimeConfig.ENTRY_ROUTER_NAME, entry.displayName());
    }

    @Test
    void competeNeedsAtLeastTwoEnabledCandidates() {
        AgentRuntimeConfig runtime = defaults();
        runtime.setMaTemplate(AgentRuntimeConfig.TEMPLATE_COMPETE);
        BizException tooFew = assertThrows(BizException.class,
                () -> MaDefPolicy.assertCompeteCandidates(runtime, List.of(candidate(20L, "a"))));
        assertTrue(tooFew.getMessage().contains("至少需要 2 个启用候选"), tooFew.getMessage());

        // 停用行不算参与竞争
        MaCandidate disabled = candidate(21L, "b");
        disabled.setEnabled(0);
        BizException disabledOnly = assertThrows(BizException.class,
                () -> MaDefPolicy.assertCompeteCandidates(runtime,
                        List.of(candidate(20L, "a"), disabled)));
        assertTrue(disabledOnly.getMessage().contains("当前 1 个"), disabledOnly.getMessage());

        assertDoesNotThrow(() -> MaDefPolicy.assertCompeteCandidates(runtime,
                List.of(candidate(20L, "a"), candidate(21L, "b"))));
        // 非 COMPETE 模板直接放行
        assertDoesNotThrow(() -> MaDefPolicy.assertCompeteCandidates(defaults(),
                List.of(candidate(20L, "a"))));
    }

    @Test
    void candidateCannotBeRouter() {
        AgentDef child = agent(20L, AgentRunMode.ROUTER);
        BizException ex = assertThrows(BizException.class,
                () -> MaDefPolicy.assertCandidates(defaults(), List.of(candidate(20L, "a")), lookup(child)));
        assertTrue(ex.getMessage().contains("子 Agent 不能是 ROUTER"), ex.getMessage());
    }

    @Test
    void candidateMustAllowOrchestrated() {
        AgentDef child = agent(20L, AgentRunMode.REACT);
        child.setAllowOrchestrated(0);
        BizException ex = assertThrows(BizException.class,
                () -> MaDefPolicy.assertCandidates(defaults(), List.of(candidate(20L, "a")), lookup(child)));
        assertTrue(ex.getMessage().contains("未允许被编排调用"), ex.getMessage());
    }

    @Test
    void routeKeyMustBeUniqueInsideScheme() {
        AgentDef child = agent(20L, AgentRunMode.REACT);
        BizException ex = assertThrows(BizException.class,
                () -> MaDefPolicy.assertCandidates(defaults(),
                        List.of(candidate(20L, "same"), candidate(20L, "SAME")), lookup(child)));
        assertTrue(ex.getMessage().contains("重复"), ex.getMessage());
    }

    @Test
    void candidateCountIsCappedAt16() {
        List<MaCandidate> list = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            list.add(candidate(100L + i, "k" + i));
        }
        // 数量硬顶在逐个查 Agent 之前就应拦住，避免先打 17 次库
        BizException ex = assertThrows(BizException.class,
                () -> MaDefPolicy.assertCandidates(defaults(), list, id -> Optional.empty()));
        assertTrue(ex.getMessage().contains("最多 16"), ex.getMessage());
    }

    /**
     * 刀5：MA 目标校验——默认接待不能进池、自己不能进池、说明为空不能进池、下探只一层。
     */
    @Test
    void maTargetsRejectLoopAndEmptyDescription() {
        MaDef target = maDef(70L, "after_sale", "售后编排", 1, null);
        MaDef reception = maDef(71L, "reception_default", "默认接待", 1, 1);
        MaDef nested = maDef(72L, "outer", "外层方案", 1, null);
        Function<Long, Optional<MaDef>> lookup = id -> {
            if (id.equals(70L)) {
                return Optional.of(target);
            }
            if (id.equals(71L)) {
                return Optional.of(reception);
            }
            if (id.equals(72L)) {
                return Optional.of(nested);
            }
            return Optional.empty();
        };
        // 目标方案自己的候选：72 号自己也挂了 MA 候选 → 非叶子
        Function<Long, List<MaCandidate>> candidatesOf = id -> id.equals(72L)
                ? List.of(maCandidate(70L, "inner"))
                : List.of();

        // 默认接待不能作为目标
        BizException loop = assertThrows(BizException.class,
                () -> MaDefPolicy.assertMaTargets(List.of(maCandidate(71L, "gw")), 1L, "ROUTER_PARALLEL",
                        1L, lookup, candidatesOf, java.util.Set.of()));
        assertTrue(loop.getMessage().contains("默认接待"), loop.getMessage());

        // 自己不能进自己的池
        BizException self = assertThrows(BizException.class,
                () -> MaDefPolicy.assertMaTargets(List.of(maCandidate(70L, "self")), 70L, "ROUTER_PARALLEL",
                        1L, lookup, candidatesOf, java.util.Set.of()));
        assertTrue(self.getMessage().contains("自己"), self.getMessage());

        // 方案套方案但没有绕回来：允许保存，深度交给运行时熔断
        assertDoesNotThrow(() -> MaDefPolicy.assertMaTargets(List.of(maCandidate(72L, "deep")), 1L,
                "ROUTER_PARALLEL", 1L, lookup, candidatesOf, java.util.Set.of()));

        // A 挂 B、B 挂 A：明确拒绝互相挂接
        Function<Long, List<MaCandidate>> mutualOf = id -> id.equals(72L)
                ? List.of(maCandidate(1L, "back"))
                : List.of();
        BizException mutual = assertThrows(BizException.class,
                () -> MaDefPolicy.assertMaTargets(List.of(maCandidate(72L, "mutual")), 1L, "ROUTER_PARALLEL",
                        1L, lookup, mutualOf, java.util.Set.of()));
        assertTrue(mutual.getMessage().contains("互相挂接"), mutual.getMessage());

        // 说明为空不能新加入
        MaDef blank = maDef(73L, "blank", null, 1, null);
        BizException empty = assertThrows(BizException.class,
                () -> MaDefPolicy.assertMaTargets(List.of(maCandidate(73L, "blank")), 1L, "ROUTER_PARALLEL",
                        1L, id -> Optional.of(blank), candidatesOf, java.util.Set.of()));
        assertTrue(empty.getMessage().contains("方案说明"), empty.getMessage());

        // 已绑定的历史目标允许说明为空（编辑旧方案时不能拦住保存）
        assertDoesNotThrow(() -> MaDefPolicy.assertMaTargets(List.of(maCandidate(73L, "blank")), 1L,
                "ROUTER_PARALLEL", 1L, id -> Optional.of(blank), candidatesOf, java.util.Set.of(73L)));

        // 停用目标不能作为路由目标
        MaDef disabled = maDef(74L, "off", "已停用", 0, null);
        BizException off = assertThrows(BizException.class,
                () -> MaDefPolicy.assertMaTargets(List.of(maCandidate(74L, "off")), 1L, "ROUTER_PARALLEL",
                        1L, id -> Optional.of(disabled), candidatesOf, java.util.Set.of()));
        assertTrue(off.getMessage().contains("已停用"), off.getMessage());

        // 不判路由的模板不能挂 MA 目标
        BizException template = assertThrows(BizException.class,
                () -> MaDefPolicy.assertMaTargets(List.of(maCandidate(70L, "x")), 1L, "FIXED_SEQUENCE",
                        1L, lookup, candidatesOf, java.util.Set.of()));
        assertTrue(template.getMessage().contains("判路由"), template.getMessage());

        // 路由后串行收到隔离：拒绝，不悄悄改成继承
        AgentRuntimeConfig isolated = new AgentRuntimeConfig();
        isolated.setMaContextPolicy("ISOLATED");
        BizException locked = assertThrows(BizException.class,
                () -> MaDefPolicy.assertSerialContextLocked("ROUTER_THEN_SEQUENCE", isolated));
        assertTrue(locked.getMessage().contains("继承前置结果"), locked.getMessage());
        isolated.setMaContextPolicy("PRIOR_SUMMARY_ONLY");
        assertDoesNotThrow(() -> MaDefPolicy.assertSerialContextLocked("ROUTER_THEN_SEQUENCE", isolated));
        isolated.setMaContextPolicy("ISOLATED");
        assertDoesNotThrow(() -> MaDefPolicy.assertSerialContextLocked("ROUTER_PARALLEL", isolated));

        // 合法：叶子 + 有说明 + 路由模板
        assertDoesNotThrow(() -> MaDefPolicy.assertMaTargets(List.of(maCandidate(70L, "ok")), 1L,
                "ROUTER_PARALLEL", 1L, lookup, candidatesOf, java.util.Set.of()));
    }

    /** 刀5：MA 候选没有子 Agent，assertCandidates 只查路由键，不再要求 agentId。 */
    @Test
    void maCandidateSkipsChildAgentChecks() {
        assertDoesNotThrow(() -> MaDefPolicy.assertCandidates(defaults(),
                List.of(maCandidate(70L, "after_sale")), id -> Optional.empty()));
        // 重复路由键仍要拦
        BizException dup = assertThrows(BizException.class,
                () -> MaDefPolicy.assertCandidates(defaults(),
                        List.of(maCandidate(70L, "same"), maCandidate(71L, "same")), id -> Optional.empty()));
        assertTrue(dup.getMessage().contains("重复"), dup.getMessage());
    }

    /** 刀5：MA 候选（无 agentId）不能误判为「缺子 Agent」。 */
    private static MaCandidate maCandidate(Long targetMaId, String key) {
        MaCandidate candidate = new MaCandidate();
        candidate.setTargetType(MaCandidate.TARGET_MA);
        candidate.setMaDefId(targetMaId);
        candidate.setRouteKey(key);
        candidate.setEnabled(1);
        return candidate;
    }

    /**
     * 刀5B：工作流目标的门闩——未发布 / 无默认发布指针 / 空说明 / 重复 / 非路由模板全部拒绝。
     *
     * <p>这四条直接对应验收口径：草稿-only、无默认发布、空说明不得加入路由池。
     */
    @Test
    void workflowTargetsRequirePublishedAndDescribed() {
        MaCandidate target = workflowCandidate(88L, "wf_reimburse");
        Function<Long, Optional<MaDefPolicy.WorkflowTargetInfo>> lookup = id -> Optional.of(
                workflowInfo(88L, "reimburse", "报销审批流程", "HAS_RELEASE", 900L));

        // 合法：已发布 + 有默认发布指针 + 有说明 + 路由模板
        assertDoesNotThrow(() -> MaDefPolicy.assertWorkflowTargets(List.of(target), "ROUTER_PARALLEL",
                1L, lookup, java.util.Set.of()));

        // 草稿-only：跑不起来，直接拒绝
        BizException draft = assertThrows(BizException.class,
                () -> MaDefPolicy.assertWorkflowTargets(List.of(target), "ROUTER_PARALLEL", 1L,
                        id -> Optional.of(workflowInfo(88L, "reimburse", "报销审批流程", "DRAFT", null)),
                        java.util.Set.of()));
        assertTrue(draft.getMessage().contains("发布"), draft.getMessage());

        // 有发布状态但默认发布指针被清掉：同样不可运行
        BizException noPointer = assertThrows(BizException.class,
                () -> MaDefPolicy.assertWorkflowTargets(List.of(target), "ROUTER_PARALLEL", 1L,
                        id -> Optional.of(workflowInfo(88L, "reimburse", "报销审批流程", "HAS_RELEASE", null)),
                        java.util.Set.of()));
        assertTrue(noPointer.getMessage().contains("发布"), noPointer.getMessage());

        // 说明为空：判路由没有依据
        BizException noDesc = assertThrows(BizException.class,
                () -> MaDefPolicy.assertWorkflowTargets(List.of(target), "ROUTER_PARALLEL", 1L,
                        id -> Optional.of(workflowInfo(88L, "reimburse", "  ", "HAS_RELEASE", 900L)),
                        java.util.Set.of()));
        assertTrue(noDesc.getMessage().contains("说明"), noDesc.getMessage());

        // 历史已绑定（保存前就在池里）允许说明为空，否则运营连改排序都做不了
        assertDoesNotThrow(() -> MaDefPolicy.assertWorkflowTargets(List.of(target), "ROUTER_PARALLEL", 1L,
                id -> Optional.of(workflowInfo(88L, "reimburse", null, "HAS_RELEASE", 900L)),
                java.util.Set.of(88L)));

        // 只允许下探一层：不判路由的模板不能挂工作流
        BizException template = assertThrows(BizException.class,
                () -> MaDefPolicy.assertWorkflowTargets(List.of(target), "FIXED_SEQUENCE", 1L, lookup,
                        java.util.Set.of()));
        assertTrue(template.getMessage().contains("判路由"), template.getMessage());

        // 同一工作流重复加入
        MaCandidate duplicated = workflowCandidate(88L, "wf_other");
        BizException dup = assertThrows(BizException.class,
                () -> MaDefPolicy.assertWorkflowTargets(List.of(target, duplicated), "ROUTER_PARALLEL", 1L,
                        lookup, java.util.Set.of()));
        assertTrue(dup.getMessage().contains("重复"), dup.getMessage());
    }

    /** 刀5B：工作流候选没有子 Agent，assertCandidates 只查路由键与目标主键。 */
    @Test
    void workflowCandidateSkipsChildAgentChecks() {
        assertDoesNotThrow(() -> MaDefPolicy.assertCandidates(defaults(),
                List.of(workflowCandidate(88L, "wf_reimburse")), id -> Optional.empty()));
        // 少 workflowDefId：明确报「必须指定工作流」，不能落成空引用
        MaCandidate broken = workflowCandidate(null, "wf_broken");
        BizException ex = assertThrows(BizException.class,
                () -> MaDefPolicy.assertCandidates(defaults(), List.of(broken), id -> Optional.empty()));
        assertTrue(ex.getMessage().contains("工作流"), ex.getMessage());
    }

    /** 刀5B：工作流候选（无 agentId）。 */
    private static MaCandidate workflowCandidate(Long workflowId, String key) {
        MaCandidate candidate = new MaCandidate();
        candidate.setTargetType(MaCandidate.TARGET_WORKFLOW);
        candidate.setWorkflowDefId(workflowId);
        candidate.setRouteKey(key);
        candidate.setEnabled(1);
        return candidate;
    }

    /** 刀5B：工作流定义的只读校验快照。 */
    private static MaDefPolicy.WorkflowTargetInfo workflowInfo(Long id, String code, String description,
                                                             String status, Long defaultReleaseId) {
        return new MaDefPolicy.WorkflowTargetInfo(id, 1L, code, code, description, status, defaultReleaseId);
    }

    /** 刀5：目标方案。isReceptionDefault=1 表示平台默认接待。 */
    private static MaDef maDef(Long id, String code, String description, int enabled, Integer receptionDefault) {
        MaDef def = new MaDef();
        def.setId(id);
        def.setTenantId(1L);
        def.setCode(code);
        def.setName(code);
        def.setDescription(description);
        def.setTemplate("ROUTER_PARALLEL");
        def.setEnabled(enabled);
        def.setIsReceptionDefault(receptionDefault);
        return def;
    }

    /** 新建一条合法的 REACT 子 Agent。 */
    private static AgentDef agent(Long id, AgentRunMode mode) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName("agent-" + id);
        agent.setCode("agent_" + id);
        agent.setRunMode(mode.name());
        agent.setStatus(AgentStatus.ENABLED);
        agent.setAllowOrchestrated(mode == AgentRunMode.REACT ? 1 : 0);
        return agent;
    }

    /** ROUTER 默认配置：只允许 REACT 子。 */
    private static AgentRuntimeConfig defaults() {
        return AgentRuntimeConfig.defaultsFor(AgentRunMode.ROUTER.name());
    }

    /** 构造一条候选请求。 */
    private static MaCandidate candidate(Long agentId, String key) {
        MaCandidate candidate = new MaCandidate();
        candidate.setAgentId(agentId);
        candidate.setRouteKey(key);
        candidate.setIntentDesc("意图 " + key);
        candidate.setEnabled(1);
        return candidate;
    }

    /** 固定返回同一个子 Agent 的查询函数。 */
    private static Function<Long, Optional<AgentDef>> lookup(AgentDef child) {
        return id -> Optional.of(child);
    }
}

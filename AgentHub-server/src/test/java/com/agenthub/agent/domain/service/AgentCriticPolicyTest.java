package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评审环角色规则（保存侧）窄测。所属域：agent。
 *
 * <p>只跑纯规则：执行者恰好 1 个、评审者从候选计数中排除、评审者非法与自评。
 * 不连库、不起 Spring。
 */
class AgentCriticPolicyTest {

    /** 评审环 + 指定评审者；轮次默认 1 */
    private static AgentRuntimeConfig criticLoop(Long criticId) {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setMaTemplate(AgentRuntimeConfig.TEMPLATE_CRITIC_LOOP);
        config.setMaCriticAgentId(criticId);
        return config;
    }

    @Test
    void nonCriticTemplateIsNotChecked() {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setMaTemplate(AgentRuntimeConfig.TEMPLATE_FIXED_PARALLEL);
        assertDoesNotThrow(() -> AgentCriticPolicy.assertRoles(config, List.of(), id -> Optional.empty()));
    }

    @Test
    void oneExecutorAndValidCriticPasses() {
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(10L, agent(10L, "执行者", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        agents.put(99L, agent(99L, "评审专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        List<AgentRoute> routes = List.of(route(10L, 10));

        assertDoesNotThrow(() -> AgentCriticPolicy.assertRoles(criticLoop(99L), routes, id ->
                Optional.ofNullable(agents.get(id))));
    }

    @Test
    void criticIsExcludedFromExecutorCount() {
        // 评审者也挂在候选表里：执行者仍然只算 1 个（评审者按 id 排除）
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(10L, agent(10L, "执行者", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        agents.put(99L, agent(99L, "评审专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        List<AgentRoute> routes = List.of(route(10L, 10), route(99L, 20));

        assertDoesNotThrow(() -> AgentCriticPolicy.assertRoles(criticLoop(99L), routes, id ->
                Optional.ofNullable(agents.get(id))));
        List<AgentDef> executors = AgentCriticPolicy.resolveExecutors(criticLoop(99L), routes, id ->
                Optional.ofNullable(agents.get(id)));
        assertEquals(1, executors.size());
        assertEquals(Long.valueOf(10L), executors.get(0).getId());
    }

    @Test
    void zeroExecutorsFailsInChinese() {
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(99L, agent(99L, "评审专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        BizException ex = assertThrows(BizException.class,
                () -> AgentCriticPolicy.assertRoles(criticLoop(99L), List.of(), id ->
                        Optional.ofNullable(agents.get(id))));
        assertTrue(ex.getMessage().contains("恰好 1 个启用中的执行者候选"), ex.getMessage());
    }

    @Test
    void twoExecutorsFailsInChinese() {
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(10L, agent(10L, "执行者一", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        agents.put(11L, agent(11L, "执行者二", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        agents.put(99L, agent(99L, "评审专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        List<AgentRoute> routes = List.of(route(10L, 10), route(11L, 20));

        BizException ex = assertThrows(BizException.class,
                () -> AgentCriticPolicy.assertRoles(criticLoop(99L), routes, id ->
                        Optional.ofNullable(agents.get(id))));
        assertTrue(ex.getMessage().contains("只允许 1 个执行者候选"), ex.getMessage());
        assertTrue(ex.getMessage().contains("执行者一") && ex.getMessage().contains("执行者二"), ex.getMessage());
    }

    @Test
    void missingCriticFails() {
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(10L, agent(10L, "执行者", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        BizException ex = assertThrows(BizException.class,
                () -> AgentCriticPolicy.assertRoles(criticLoop(null), List.of(route(10L, 10)), id ->
                        Optional.ofNullable(agents.get(id))));
        assertTrue(ex.getMessage().contains("需要选择评审专家"), ex.getMessage());
    }

    @Test
    void criticSameAsExecutorFails() {
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(10L, agent(10L, "执行者", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        BizException ex = assertThrows(BizException.class,
                () -> AgentCriticPolicy.assertRoles(criticLoop(10L), List.of(route(10L, 10)), id ->
                        Optional.ofNullable(agents.get(id))));
        assertTrue(ex.getMessage().contains("评审专家不能与执行者相同"), ex.getMessage());
    }

    @Test
    void disabledCriticFails() {
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(10L, agent(10L, "执行者", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        agents.put(99L, agent(99L, "评审专家", AgentRunMode.REACT.name(), AgentStatus.DISABLED, true, null));
        BizException ex = assertThrows(BizException.class,
                () -> AgentCriticPolicy.assertRoles(criticLoop(99L), List.of(route(10L, 10)), id ->
                        Optional.ofNullable(agents.get(id))));
        assertTrue(ex.getMessage().contains("已停用"), ex.getMessage());
    }

    @Test
    void criticWithoutAllowOrchestratedFails() {
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(10L, agent(10L, "执行者", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        agents.put(99L, agent(99L, "评审专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, false, null));
        BizException ex = assertThrows(BizException.class,
                () -> AgentCriticPolicy.assertRoles(criticLoop(99L), List.of(route(10L, 10)), id ->
                        Optional.ofNullable(agents.get(id))));
        assertTrue(ex.getMessage().contains("未允许被编排调用"), ex.getMessage());
    }

    @Test
    void routerCriticRejected() {
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(10L, agent(10L, "执行者", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        agents.put(99L, agent(99L, "另一个路由器", AgentRunMode.ROUTER.name(), AgentStatus.ENABLED, true, null));
        String problem = AgentCriticPolicy.criticProblem(criticLoop(99L), agents.get(99L));
        assertTrue(problem != null && problem.contains("不能是 ROUTER"), problem);
    }

    @Test
    void criticProblemNullWhenValid() {
        AgentDef critic = agent(99L, "评审专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null);
        assertNull(AgentCriticPolicy.criticProblem(criticLoop(99L), critic));
    }

    @Test
    void planExecutorRequiresAllowedMode() {
        // 执行者是 P&E 但编排只允许 REACT：不计入执行者，等同 0 个（与运行时 usable 同口径）
        Map<Long, AgentDef> agents = new HashMap<>();
        agents.put(10L, agent(10L, "计划执行者", AgentRunMode.PLAN_AND_EXECUTE.name(),
                AgentStatus.ENABLED, true, null));
        agents.put(99L, agent(99L, "评审专家", AgentRunMode.REACT.name(), AgentStatus.ENABLED, true, null));
        BizException ex = assertThrows(BizException.class,
                () -> AgentCriticPolicy.assertRoles(criticLoop(99L), List.of(route(10L, 10)), id ->
                        Optional.ofNullable(agents.get(id))));
        assertTrue(ex.getMessage().contains("恰好 1 个启用中的执行者候选"), ex.getMessage());
    }

    private static AgentRoute route(Long childId, int sortOrder) {
        AgentRoute route = new AgentRoute();
        route.setId(childId + 100);
        route.setParentAgentId(1L);
        route.setChildAgentId(childId);
        route.setSortOrder(sortOrder);
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    private static AgentDef agent(Long id, String name, String runMode, int status,
                                  boolean allowOrchestrated, String runtimeJson) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode(name);
        agent.setRunMode(runMode);
        agent.setStatus(status);
        agent.setAllowOrchestrated(allowOrchestrated ? 1 : 0);
        agent.setRuntimeConfigJson(runtimeJson);
        return agent;
    }
}

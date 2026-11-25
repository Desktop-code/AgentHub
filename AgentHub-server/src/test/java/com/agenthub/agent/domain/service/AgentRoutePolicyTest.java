package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一层路由只接受已启用的 REACT 子节点，并且不能指向自己。
 */
class AgentRoutePolicyTest {

    @Test
    void rejectsSelfRouterChildAndDisabledChild() {
        AgentDef parent = agent(1L, AgentRunMode.ROUTER.name(), AgentStatus.ENABLED);
        assertThrows(BizException.class, () -> AgentRoutePolicy.assertCanBind(parent, parent));
        assertTrue(messageOf(() -> AgentRoutePolicy.assertCanBind(parent,
                agent(1L, AgentRunMode.REACT.name(), AgentStatus.ENABLED))).contains("自己"));
        assertTrue(messageOf(() -> AgentRoutePolicy.assertCanBind(parent,
                agent(2L, AgentRunMode.ROUTER.name(), AgentStatus.ENABLED))).contains("ROUTER"));
        assertTrue(messageOf(() -> AgentRoutePolicy.assertCanBind(parent,
                agent(3L, AgentRunMode.REACT.name(), AgentStatus.DISABLED))).contains("启用"));
        assertTrue(messageOf(() -> AgentRoutePolicy.assertCanBind(
                agent(4L, AgentRunMode.REACT.name(), AgentStatus.ENABLED),
                agent(5L, AgentRunMode.REACT.name(), AgentStatus.ENABLED))).contains("ROUTER"));
    }

    @Test
    void acceptsEnabledReactChild() {
        assertDoesNotThrow(() -> AgentRoutePolicy.assertCanBind(
                agent(1L, AgentRunMode.ROUTER.name(), AgentStatus.DISABLED),
                agent(2L, AgentRunMode.REACT.name(), AgentStatus.ENABLED)));
    }

    /**
     * 私有 Agent 保护：同为启用 REACT，但 allow_orchestrated=0 时不允许被挂为子。
     * 断言文案里带 Agent 名称，便于用户直接定位是哪一个。
     */
    @Test
    void rejectsChildNotAllowingOrchestration() {
        AgentDef parent = agent(1L, AgentRunMode.ROUTER.name(), AgentStatus.ENABLED);
        AgentDef child = agent(2L, AgentRunMode.REACT.name(), AgentStatus.ENABLED);
        child.setName("私有专家");
        child.setAllowOrchestrated(0);

        String message = messageOf(() -> AgentRoutePolicy.assertCanBind(parent, child));
        assertTrue(message.contains("私有专家"), message);
        assertTrue(message.contains("未允许被编排调用"), message);
    }

    /**
     * 旧数据/未迁移时 allow_orchestrated 为空：非 ROUTER 视为允许，不能把存量候选全判死。
     */
    @Test
    void nullAllowFlagOnReactChildIsTreatedAsAllowed() {
        AgentDef child = agent(2L, AgentRunMode.REACT.name(), AgentStatus.ENABLED);
        child.setAllowOrchestrated(null);
        assertDoesNotThrow(() -> AgentRoutePolicy.assertCanBind(
                agent(1L, AgentRunMode.ROUTER.name(), AgentStatus.ENABLED), child));
    }

    /**
     * ROUTER 子本身就是父节点，即便列值被写成 1 也必须拒绝（双重保险）。
     */
    @Test
    void routerChildIsRejectedEvenIfAllowFlagIsOne() {
        AgentDef child = agent(2L, AgentRunMode.ROUTER.name(), AgentStatus.ENABLED);
        child.setAllowOrchestrated(1);
        assertTrue(messageOf(() -> AgentRoutePolicy.assertCanBind(
                agent(1L, AgentRunMode.ROUTER.name(), AgentStatus.ENABLED), child)).contains("ROUTER"));
    }

    /**
     * 子 P&E 门闩：父编排未勾选「计划执行」时必须拒写，文案指向策略区。
     */
    @Test
    void rejectsPlanChildWhenParentDoesNotAllowIt() {
        AgentDef parent = agent(1L, AgentRunMode.ROUTER.name(), AgentStatus.ENABLED);
        AgentDef child = agent(2L, AgentRunMode.PLAN_AND_EXECUTE.name(), AgentStatus.ENABLED);
        child.setAllowOrchestrated(1);

        String message = messageOf(() -> AgentRoutePolicy.assertCanBind(parent, child));
        assertTrue(message.contains("未允许子使用计划执行模式"), message);
        assertTrue(message.contains("允许的子运行模式"), message);
    }

    /**
     * 父编排显式允许 P&E 且子其它门闩都过：应放行。
     */
    @Test
    void acceptsPlanChildWhenParentAllowsIt() {
        AgentDef parent = agent(1L, AgentRunMode.ROUTER.name(), AgentStatus.ENABLED);
        parent.setRuntimeConfigJson("{\"maChildRunModesAllowed\":[\"REACT\",\"PLAN_AND_EXECUTE\"]}");
        AgentDef child = agent(2L, AgentRunMode.PLAN_AND_EXECUTE.name(), AgentStatus.ENABLED);
        child.setAllowOrchestrated(1);

        assertDoesNotThrow(() -> AgentRoutePolicy.assertCanBind(parent, child));
    }

    /**
     * 门闩叠加：即便父编排允许 P&E，子没开 allow_orchestrated 仍必须拒。
     */
    @Test
    void planChildStillNeedsAllowOrchestrated() {
        AgentDef parent = agent(1L, AgentRunMode.ROUTER.name(), AgentStatus.ENABLED);
        parent.setRuntimeConfigJson("{\"maChildRunModesAllowed\":[\"REACT\",\"PLAN_AND_EXECUTE\"]}");
        AgentDef child = agent(2L, AgentRunMode.PLAN_AND_EXECUTE.name(), AgentStatus.ENABLED);
        child.setName("计划专家");
        child.setAllowOrchestrated(0);

        String message = messageOf(() -> AgentRoutePolicy.assertCanBind(parent, child));
        assertTrue(message.contains("计划专家"), message);
        assertTrue(message.contains("未允许被编排调用"), message);
    }

    private static String messageOf(Runnable call) {        return assertThrows(BizException.class, call::run).getMessage();
    }

    private static AgentDef agent(Long id, String runMode, int status) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setRunMode(runMode);
        agent.setStatus(status);
        return agent;
    }
}

package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.AgentRuntimeConfigReq;
import com.agenthub.agent.api.dto.AgentUpdateReq;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentModelBindRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.domain.service.AgentCodePolicy;
import com.agenthub.agent.domain.service.AgentPromptProperties;
import com.agenthub.agent.domain.service.AgentPromptTemplatePort;
import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.common.config.RuntimeLimitsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排根保存「允许的子运行模式」时的反向校验。所属域：agent（test）。
 *
 * <p>只需要证明一件事：把 allowed 缩回仅 REACT 时，如果库里还挂着 P&E 候选，保存必须失败，
 * 不能留下「校验区报错但配置已落库」的矛盾状态；候选都是 REACT 时必须能正常保存。
 * 不连库、不出站模型。
 */
class AgentAppServiceChildModesTest {

    private static final long PARENT_ID = 9L;
    private static final long CHILD_ID = 2L;

    private final AgentDefRepository agents = mock(AgentDefRepository.class);
    private final AgentRouteRepository routes = mock(AgentRouteRepository.class);
    private AgentAppService service;
    private AgentDef parent;

    @BeforeEach
    void setUp() {
        AgentPromptProperties properties = new AgentPromptProperties();
        PlatformPromptPrefix prefixes = com.agenthub.prompt.PromptTestFixtures.prefix();
        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(1L);

        parent = new AgentDef();
        parent.setId(PARENT_ID);
        parent.setName("总控");
        parent.setCode("parent");
        parent.setRunMode(AgentRunMode.ROUTER.name());
        parent.setStatus(AgentStatus.ENABLED);
        // 初始已允许 P&E：模拟「先勾了计划执行、挂了 P&E 候选」的库内状态
        parent.setRuntimeConfigJson("{\"maChildRunModesAllowed\":[\"REACT\",\"PLAN_AND_EXECUTE\"]}");
        when(agents.findById(PARENT_ID)).thenReturn(Optional.of(parent));

        RuntimeLimitsProperties limits = new RuntimeLimitsProperties();
        service = new AgentAppService(agents, mock(AgentModelBindRepository.class),
                mock(AgentToolBindRepository.class), mock(AgentSkillBindRepository.class), routes,
                new AgentCodePolicy(agents), new AgentLookup(agents), operator, mock(OperLogWriter.class),
                new PromptSafetyChecker(properties), prefixes, new AgentLimitValidator(limits), limits,
                mock(AgentPromptTemplatePort.class));
    }

    @Test
    void closingPlanModeIsRejectedWhilePlanChildStillBound() {
        givenBoundChild(AgentRunMode.PLAN_AND_EXECUTE);
        AgentUpdateReq req = updateRequest(false);

        BizException ex = assertThrows(BizException.class, () -> service.update(PARENT_ID, req));
        assertTrue(ex.getMessage().contains("计划执行"), ex.getMessage());
        assertTrue(ex.getMessage().contains("请先移除或停用该候选"), ex.getMessage());
        // 校验失败必须整笔不落库：不能出现「配置已改、候选还挂着」
        verify(agents, never()).updateProfile(any(), anyLong());
    }

    @Test
    void closingPlanModeIsAllowedWhenOnlyReactChildrenBound() {
        givenBoundChild(AgentRunMode.REACT);

        assertDoesNotThrow(() -> service.update(PARENT_ID, updateRequest(false)));
        verify(agents).updateProfile(any(), anyLong());
    }

    /** 挂一条候选并准备子 Agent 定义。 */
    private void givenBoundChild(AgentRunMode childMode) {
        AgentRoute route = new AgentRoute();
        route.setId(100L);
        route.setParentAgentId(PARENT_ID);
        route.setChildAgentId(CHILD_ID);
        route.setRouteKey("child");
        route.setStatus(AgentStatus.ENABLED);
        when(routes.listByParentId(PARENT_ID)).thenReturn(List.of(route));

        AgentDef child = new AgentDef();
        child.setId(CHILD_ID);
        child.setName("专家");
        child.setCode("child");
        child.setRunMode(childMode.name());
        child.setStatus(AgentStatus.ENABLED);
        when(agents.findById(CHILD_ID)).thenReturn(Optional.of(child));
    }

    /**
     * 编排 Tab 的整份保存请求：只改允许集合。
     *
     * @param allowPlan 是否保留「计划执行」
     * @return 请求体
     */
    private AgentUpdateReq updateRequest(boolean allowPlan) {
        AgentUpdateReq req = new AgentUpdateReq();
        req.setName("总控");
        req.setCode("parent");
        req.setRunMode(AgentRunMode.ROUTER.name());
        AgentRuntimeConfigReq config = new AgentRuntimeConfigReq();
        config.setMaChildRunModesAllowed(allowPlan
                ? List.of("REACT", "PLAN_AND_EXECUTE")
                : List.of("REACT"));
        req.setRuntimeConfig(config);
        return req;
    }
}

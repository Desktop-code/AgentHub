package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刀4：意图说明单源 + 内置判路由指令。所属域：runtime。
 *
 * <p>只验证提示词组装与判路由结果映射，不起 Spring、不连库、不出站模型：
 * <ol>
 *   <li>提示词里的职责简介只来自 Agent 简介，不再读 ma_candidate.intent_desc；</li>
 *   <li>默认接待的提示词带内置【可用专家】/【调度规则】，兜底目标明确是接待助手；</li>
 *   <li>寒暄命中接待助手，业务描述命中对应专家（模型返回由 mock 给出，只验链路）；</li>
 *   <li>非默认接待方案不出现「接待助手」字样，避免误导其它编排。</li>
 * </ol>
 */
class ReceptionRoutingPromptTest {

    private static final long ASSISTANT_ID = 10L;
    private static final long ORDER_ID = 20L;

    private TrialBindingLoader loader;
    private ReActTrialExecutor react;
    private SystemPromptAssembler prompts;
    private RuntimeLimitResolver limitResolver;
    private TrialMemoryCoordinator memory;
    private PlanTrialExecutor planRunner;
    private ThreadPoolTaskExecutor pool;
    private RouterTrialExecutor executor;

    private final EffectiveLimits limits = new EffectiveLimits(
            60_000L, 6, 10_000L, 1, 20, 10_000L, 20, 3, 4000);

    @BeforeEach
    void setUp() {
        AgentRouteRepository routes = mock(AgentRouteRepository.class);
        AgentDefRepository agents = mock(AgentDefRepository.class);
        loader = mock(TrialBindingLoader.class);
        react = mock(ReActTrialExecutor.class);
        prompts = mock(SystemPromptAssembler.class);
        limitResolver = mock(RuntimeLimitResolver.class);
        memory = mock(TrialMemoryCoordinator.class);
        planRunner = mock(PlanTrialExecutor.class);
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(8);
        pool.initialize();
        executor = new RouterTrialExecutor(routes, agents, loader, react, prompts, limitResolver, memory,
                planRunner, new ChildPlanApprovalRegistry(), pool);

        when(limitResolver.resolve(any())).thenReturn(limits);
        when(prompts.build(any(), any())).thenReturn("");
        when(memory.recallDurable(any(), any())).thenReturn(new TrialMemoryCoordinator.Augmented("", List.of()));
        TrialPlan plan = new TrialPlan();
        plan.setLimits(limits);
        plan.setNotices(new ArrayList<>());
        when(loader.loadReact(any(), anyString(), any())).thenReturn(plan);
        when(loader.findRouterModel(anyLong())).thenReturn(Optional.empty());
        when(agents.findById(ASSISTANT_ID)).thenReturn(Optional.of(child(ASSISTANT_ID, "接待助手", "reception_assistant",
                "平台默认接待助手：寒暄、能力介绍，说不清先追问一句")));
        when(agents.findById(ORDER_ID)).thenReturn(Optional.of(child(ORDER_ID, "订单助手", "order",
                "查询订单、物流与退款进度")));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("子答"));
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /** 提示词里只出现 Agent 简介，不出现候选列里那份 intent_desc。 */
    @Test
    void judgePromptReadsAgentDescriptionInsteadOfCandidateIntentDesc() {
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"order\",\"sub_query\":\"查订单\"}]");

        executor.execute(parent(), receptionSpec(), "查一下我的订单", "查一下我的订单", 7L, 1L, null, null);

        String prompt = captureJudgePrompt();
        assertTrue(prompt.contains("查询订单、物流与退款进度"), prompt);
        // 候选行里写的是「意图order」，一旦它出现在提示词里就说明还在读 intent_desc
        assertFalse(prompt.contains("意图order"), prompt);
        assertFalse(prompt.contains("意图reception_assistant"), prompt);
    }

    /**
     * 内置结构：网关指令 + 可用专家 + 调度规则，且兜底点名「聊天机器人」。
     *
     * <p>文案已从「接待助手」收敛为「聊天机器人」（它只负责寒暄与无人能接时的闲聊）；
     * 这里钉住「键（聊天机器人「Agent 名」）」的完整形状，防止以后又改回规划选人的口吻。
     */
    @Test
    void receptionPromptHasBuiltInGatewayStructure() {
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"order\",\"sub_query\":\"查订单\"}]");

        executor.execute(parent(), receptionSpec(), "查一下我的订单", "查一下我的订单", 7L, 2L, null, null);

        String prompt = captureJudgePrompt();
        assertTrue(prompt.contains("调度网关"), prompt);
        assertTrue(prompt.contains("【可用专家】"), prompt);
        assertTrue(prompt.contains("【调度规则】"), prompt);
        // 只输出候选内的键 + 兜底派给聊天机器人
        assertTrue(prompt.contains("只能取自上面的【可用专家】"), prompt);
        assertTrue(prompt.contains("reception_assistant（聊天机器人「接待助手」）"), prompt);
        assertTrue(prompt.contains("不得指向名单外的 Agent、另一个 Router"), prompt);
    }

    /** 寒暄派给接待助手；业务描述派给对应专家（模型返回由 mock 给出）。 */
    @Test
    void greetingGoesToAssistantAndBusinessGoesToExpert() {
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"reception_assistant\",\"sub_query\":\"你好\"}]");
        RouterTrialExecutor.RoutedTurn greeting = executor.execute(parent(), receptionSpec(),
                "你好", "你好", 7L, 3L, null, null);
        assertEquals(ASSISTANT_ID, greeting.sticky().agentId());

        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"order\",\"sub_query\":\"我的包裹到哪了\"}]");
        RouterTrialExecutor.RoutedTurn business = executor.execute(parent(), receptionSpec(),
                "我的包裹到哪了", "我的包裹到哪了", 7L, 4L, null, null);
        assertEquals(ORDER_ID, business.sticky().agentId());
        assertTrue(business.turn().getAssistantText().contains("子答"));
    }

    /** 普通方案不出现接待口径，避免把其它编排也当成工作台网关。 */
    @Test
    void nonReceptionSchemeDoesNotMentionReceptionAssistant() {
        when(react.judge(any(), any(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"order\",\"sub_query\":\"查订单\"}]");

        executor.execute(parent(), ordinarySpec(), "查订单", "查订单", 7L, 5L, null, null);

        String prompt = captureJudgePrompt();
        // 兜底文案按「首位候选」表述，而不是「接待助手」
        assertTrue(prompt.contains("，首位候选）"), prompt);
        assertFalse(prompt.contains("（接待助手）"), prompt);
    }

    private String captureJudgePrompt() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(react).judge(any(), prompt.capture(), anyString(), any());
        return prompt.getValue();
    }

    /** 虚拟根的只读壳：与 TrialRunAppService 合成的形状一致（id 为空）。 */
    private AgentDef parent() {
        AgentDef agent = new AgentDef();
        agent.setName("默认接待");
        agent.setRunMode(AgentRunMode.ROUTER.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    private AgentDef child(Long id, String name, String code, String description) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode(code);
        agent.setDescription(description);
        agent.setRunMode(AgentRunMode.REACT.name());
        agent.setStatus(AgentStatus.ENABLED);
        agent.setAllowOrchestrated(1);
        return agent;
    }

    /** 默认接待方案：第一条是接待助手。 */
    private MaRunSpec receptionSpec() {
        return spec(true);
    }

    /** 普通方案：同候选池，但不是平台默认接待。 */
    private MaRunSpec ordinarySpec() {
        return spec(false);
    }

    private MaRunSpec spec(boolean receptionDefault) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name());
        runtime.setMaTemplate("ROUTER_PARALLEL");
        List<MaCandidate> candidates = List.of(
                candidate(ASSISTANT_ID, "reception_assistant", 1),
                candidate(ORDER_ID, "order", 2));
        return new MaRunSpec(5L, "reception_default", "默认接待", "ROUTER_PARALLEL", runtime,
                candidates, null, 99L, receptionDefault);
    }

    private MaCandidate candidate(Long agentId, String key, int sortNo) {
        MaCandidate item = new MaCandidate();
        item.setId(agentId + 1000);
        item.setMaId(5L);
        item.setAgentId(agentId);
        item.setRouteKey(key);
        // 故意与 Agent 简介不同：提示词里出现它说明还在读旧列
        item.setIntentDesc("意图" + key);
        item.setSortNo(sortNo);
        item.setEnabled(1);
        return item;
    }

    private TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}

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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入口点名「企业综合台」或一句里同时要制度与报告时，分给方案，不截给知识问答。
 *
 * <p>所属域：runtime。模型返回由 mock 固定成「只选知识问答」，用来证明纠偏不靠模型听话。
 * 不连库，不改 preferSchemes。
 */
class EntryRouterDispatchTest {

    private static final long ASSISTANT_ID = 10L;
    private static final long KNOWLEDGE_ID = 30L;
    private static final long POLICY_ID = 41L;
    private static final long REPORT_ID = 42L;
    private static final long SCHEME_ID = 70L;

    private static final String NAMED = "请企业综合台根据公司制度整理知识要点，并起草一份报告";
    private static final String MULTI = "根据公司制度整理知识要点，并起草一份报告";
    private static final String SINGLE = "年假制度是怎么规定的";

    private ReActTrialExecutor react;
    private RouterTrialExecutor executor;
    private ThreadPoolTaskExecutor pool;

    private final EffectiveLimits limits = new EffectiveLimits(
            60_000L, 6, 10_000L, 1, 20, 10_000L, 20, 3, 4000);

    @BeforeEach
    void setUp() {
        AgentDefRepository agents = mock(AgentDefRepository.class);
        react = mock(ReActTrialExecutor.class);
        RuntimeLimitResolver limitResolver = mock(RuntimeLimitResolver.class);
        TrialBindingLoader loader = mock(TrialBindingLoader.class);
        TrialMemoryCoordinator memory = mock(TrialMemoryCoordinator.class);
        SystemPromptAssembler prompts = mock(SystemPromptAssembler.class);
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(8);
        pool.initialize();
        executor = new RouterTrialExecutor(mock(AgentRouteRepository.class), agents, loader, react, prompts,
                limitResolver, memory, mock(PlanTrialExecutor.class), new ChildPlanApprovalRegistry(), pool);

        when(limitResolver.resolve(nullable(AgentDef.class))).thenReturn(limits);
        when(memory.recallDurable(any(), any())).thenReturn(new TrialMemoryCoordinator.Augmented("", List.of()));
        TrialPlan plan = new TrialPlan();
        plan.setLimits(limits);
        plan.setNotices(new ArrayList<>());
        when(loader.loadReact(any(), anyString(), any())).thenReturn(plan);
        when(agents.findById(ASSISTANT_ID)).thenReturn(Optional.of(agent(ASSISTANT_ID, "接待助手", "寒暄与说不清时的追问")));
        when(agents.findById(KNOWLEDGE_ID)).thenReturn(Optional.of(agent(KNOWLEDGE_ID, "知识问答", "回答知识与制度类问题")));
        when(agents.findById(POLICY_ID)).thenReturn(Optional.of(agent(POLICY_ID, "制度知识专家", "检索制度与知识")));
        when(agents.findById(REPORT_ID)).thenReturn(Optional.of(agent(REPORT_ID, "报告撰写专家", "起草正式报告")));
        when(agents.findByIds(any())).thenReturn(List.of(
                agent(POLICY_ID, "制度知识专家", "检索制度与知识"),
                agent(REPORT_ID, "报告撰写专家", "起草正式报告")));
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("子答"));
        when(react.judge(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(1);
            String user = invocation.getArgument(2);
            if (prompt.contains("制度知识专家")) {
                return "[{\"route_key\":\"policy\",\"sub_query\":\"制度与知识\"},"
                        + "{\"route_key\":\"report\",\"sub_query\":\"起草报告\"}]";
            }
            if ("你好".equals(user)) {
                return "[{\"route_key\":\"reception_assistant\",\"sub_query\":\"你好\"}]";
            }
            return "[{\"route_key\":\"knowledge\",\"sub_query\":\"只留知识问答\"}]";
        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /** 点名方案：模型就算只肯返回知识问答，入口摘要也必须是企业综合台，并且整句交给方案再分。 */
    @Test
    void namedSchemeIsNotStolenByKnowledgeExpert() {
        RouterTrialExecutor.RoutedTurn turn = executor.execute(parent(), entry(), NAMED, NAMED, 7L, 1L, null, null);

        assertTrue(turn.summary().contains("企业综合台"), turn.summary());
        assertFalse(turn.summary().contains("知识问答"), turn.summary());
        CapturedJudge judge = captureJudge();
        String joined = String.join("\n", judge.prompts);
        assertTrue(joined.contains("制度知识专家"), joined);
        assertTrue(joined.contains("报告撰写专家"), joined);
        assertTrue(judge.users.stream().anyMatch(NAMED::equals), judge.users.toString());
    }

    /** 没点名，但制度与报告两项都在：同样交给综合台，不留下模型选中的知识问答。 */
    @Test
    void twoDeliverablesGoToSchemeEvenIfModelPicksOneExpert() {
        RouterTrialExecutor.RoutedTurn turn = executor.execute(parent(), entry(), MULTI, MULTI, 7L, 2L, null, null);

        assertTrue(turn.summary().contains("企业综合台"), turn.summary());
        assertFalse(turn.summary().contains("知识问答"), turn.summary());
    }

    /** 只问制度：不塞进综合台，模型选中的知识问答可以留下。 */
    @Test
    void singlePolicyQuestionCanStayWithExpert() {
        RouterTrialExecutor.RoutedTurn turn = executor.execute(parent(), entry(), SINGLE, SINGLE, 7L, 3L, null, null);

        assertTrue(turn.summary().contains("知识问答"), turn.summary());
        assertFalse(turn.summary().contains("企业综合台"), turn.summary());
    }

    /** 闲聊仍走接待助手；入口提示词里写着点名方案的规则，但不因此改派。 */
    @Test
    void greetingStaysWithAssistant() {
        RouterTrialExecutor.RoutedTurn turn = executor.execute(parent(), entry(), "你好", "你好", 7L, 4L, null, null);

        assertTrue(turn.summary().contains("接待助手"), turn.summary());
        String prompt = String.join("\n", captureJudge().prompts);
        assertTrue(prompt.contains("只派给该方案"), prompt);
        assertTrue(prompt.contains("多项交付"), prompt);
    }

    /** 普通方案即使问句带了方案名，也不走入口纠偏。这里没有入口模板，模型结果原样留下。 */
    @Test
    void ordinarySchemeDoesNotApplyEntryPreference() {
        MaRunSpec ordinary = new MaRunSpec(8L, "scene", "场景", "ROUTER_PARALLEL",
                runtime("ROUTER_PARALLEL"), List.of(agentRow(KNOWLEDGE_ID, "knowledge", 1)),
                null, 99L, false);
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"knowledge\",\"sub_query\":\"查一下\"}]");

        RouterTrialExecutor.RoutedTurn turn = executor.execute(parent(), ordinary, NAMED, NAMED, 7L, 5L, null, null);

        assertTrue(turn.summary().contains("知识问答"), turn.summary());
        verify(react, atLeastOnce()).judge(any(), anyString(), anyString(), any());
    }

    private CapturedJudge captureJudge() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(react, atLeastOnce()).judge(any(), prompt.capture(), user.capture(), any());
        return new CapturedJudge(prompt.getAllValues(), user.getAllValues());
    }

    private record CapturedJudge(List<String> prompts, List<String> users) {
    }

    private AgentDef parent() {
        AgentDef agent = new AgentDef();
        agent.setName("Router");
        agent.setRunMode(AgentRunMode.ROUTER.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    private MaRunSpec entry() {
        AgentRuntimeConfig config = runtime(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER);
        MaRunSpec scheme = new MaRunSpec(SCHEME_ID, "enterprise", "企业综合台",
                "制度检索；报告起草", "ROUTER_PARALLEL", runtime("ROUTER_PARALLEL"),
                List.of(agentRow(POLICY_ID, "policy", 1), agentRow(REPORT_ID, "report", 2)),
                null, 99L, false, Map.of(), Map.of());
        return new MaRunSpec(1L, "entry", "Router", "对话入口", AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER,
                config, List.of(
                agentRow(ASSISTANT_ID, "reception_assistant", 1),
                agentRow(KNOWLEDGE_ID, "knowledge", 2),
                schemeRow()),
                null, 99L, true, Map.of(SCHEME_ID, scheme), Map.of());
    }

    private static AgentRuntimeConfig runtime(String template) {
        AgentRuntimeConfig config = AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name());
        config.setMaTemplate(template);
        return config;
    }

    private static MaCandidate agentRow(long agentId, String key, int sortNo) {
        MaCandidate item = new MaCandidate();
        item.setId(agentId + 1000);
        item.setMaId(1L);
        item.setAgentId(agentId);
        item.setRouteKey(key);
        item.setSortNo(sortNo);
        item.setEnabled(1);
        return item;
    }

    private static MaCandidate schemeRow() {
        MaCandidate item = new MaCandidate();
        item.setId(7000L);
        item.setMaId(1L);
        item.setTargetType(MaCandidate.TARGET_MA);
        item.setMaDefId(SCHEME_ID);
        item.setRouteKey("enterprise");
        item.setSortNo(3);
        item.setEnabled(1);
        return item;
    }

    private static AgentDef agent(long id, String name, String description) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode(name);
        agent.setDescription(description);
        agent.setRunMode(AgentRunMode.REACT.name());
        agent.setStatus(AgentStatus.ENABLED);
        agent.setAllowOrchestrated(1);
        return agent;
    }

    private static TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}

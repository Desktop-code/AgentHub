package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.runtime.application.router.WorkflowTargetRunner;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.TrialProgress;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作台入口「一条消息多个问题拆开分流」的执行窄测。所属域：runtime。
 *
 * <p>入口种子的运行配置是 {@code multiIntentEnabled=false / maxRouteChildren=1}：
 * 本类故意沿用它，证明拆问不靠数据库改配置，也不会因为该上限把后一问截掉。
 * 全部 mock、不出站模型、不连库。
 */
class EntryMultiQuestionDispatchTest {

    private static final long ASSISTANT_ID = 10L;
    private static final long KNOWLEDGE_ID = 30L;
    private static final long POLICY_ID = 41L;
    private static final long REPORT_ID = 42L;
    private static final long SCHEME_ID = 70L;
    private static final long WORKFLOW_ID = 88L;

    private static final String BOTH = "请企业综合台根据公司制度整理知识要点，并起草一份报告。同时也按工作流跑一次";
    private static final String TWO = "年假制度是怎么规定的。再请工作流出个报告";
    private static final String SINGLE_MULTI = "根据公司制度整理知识要点，并起草一份报告";
    private static final String SINGLE = "年假制度是怎么规定的";

    private ReActTrialExecutor react;
    private WorkflowTargetRunner workflowTargets;
    private RouterTrialExecutor executor;
    private ThreadPoolTaskExecutor pool;
    private AgentDefRepository agents;

    private final EffectiveLimits limits = new EffectiveLimits(
            60_000L, 6, 10_000L, 1, 20, 10_000L, 20, 3, 4000);

    @BeforeEach
    void setUp() {
        agents = mock(AgentDefRepository.class);
        react = mock(ReActTrialExecutor.class);
        RuntimeLimitResolver limitResolver = mock(RuntimeLimitResolver.class);
        TrialBindingLoader loader = mock(TrialBindingLoader.class);
        TrialMemoryCoordinator memory = mock(TrialMemoryCoordinator.class);
        SystemPromptAssembler prompts = mock(SystemPromptAssembler.class);
        workflowTargets = mock(WorkflowTargetRunner.class);
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(3);
        pool.setMaxPoolSize(3);
        pool.setQueueCapacity(16);
        pool.initialize();
        executor = new RouterTrialExecutor(mock(AgentRouteRepository.class), agents, loader, react, prompts,
                limitResolver, memory, mock(PlanTrialExecutor.class), new ChildPlanApprovalRegistry(), pool,
                workflowTargets);

        when(limitResolver.resolve(nullable(AgentDef.class))).thenReturn(limits);
        when(memory.recallDurable(any(), any())).thenReturn(new TrialMemoryCoordinator.Augmented("", List.of()));
        TrialPlan plan = new TrialPlan();
        plan.setLimits(limits);
        plan.setNotices(new ArrayList<>());
        when(loader.loadReact(any(), anyString(), any())).thenReturn(plan);
        when(agents.findById(ASSISTANT_ID))
                .thenReturn(Optional.of(agent(ASSISTANT_ID, "接待助手", "寒暄与说不清时的追问")));
        when(agents.findById(KNOWLEDGE_ID))
                .thenReturn(Optional.of(agent(KNOWLEDGE_ID, "知识问答", "回答知识与制度类问题")));
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
                // 方案内部仍按既有口径选人：这里只给一位，避免把内层也变多意图
                return "[{\"route_key\":\"policy\",\"sub_query\":\"整理制度要点\"}]";
            }
            if (user.contains("工作流")) {
                return "[{\"route_key\":\"wf_report\",\"sub_query\":\"出一份报告\"}]";
            }
            if ("你好".equals(user)) {
                return "[{\"route_key\":\"reception_assistant\",\"sub_query\":\"你好\"}]";
            }
            return "[{\"route_key\":\"knowledge\",\"sub_query\":\"只留知识问答\"}]";
        });
        when(workflowTargets.run(any(), any(), anyString(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new WorkflowTargetRunner.WorkflowTargetResult(turn("工作流已出报告"),
                        List.of("运行工作流「制度要点与报告」…")));
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /**
     * 一句点名方案 + 一句要求工作流：两问都真实执行，入口块按问列出两边去向。
     */
    @Test
    void namedSchemeAndWorkflowBothRun() {
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), entry(), BOTH, BOTH, 7L, 11L, null, null);

        // 入口决策块：两问、按序、两边的名字都在
        assertTrue(routed.decisionBlock().contains("已拆成 2 个问题"), routed.decisionBlock());
        assertTrue(routed.summary().contains("企业综合台"), routed.summary());
        assertTrue(routed.summary().contains("制度要点与报告"), routed.summary());
        // 工作流真的被拉起，且拿到的是第一问的内容（第二问自身没有内容）
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(workflowTargets, times(1)).run(any(), any(), query.capture(), anyLong(), anyLong(), anyLong(), any());
        assertTrue(query.getValue().contains("根据公司制度整理知识要点"), query.getValue());
        // 方案内部也真的跑了：内层判路由 + 子执行都在
        verify(react, atLeastOnce()).judge(any(), anyString(), anyString(), any());
        verify(react, atLeastOnce()).execute(any(), anyString(), anyLong(), anyLong(), any());
        // 两问各自的正文都在终答里，且没有被跨问汇总成一段「方案结论」
        assertTrue(routed.turn().getAssistantText().contains("工作流已出报告"), routed.turn().getAssistantText());
    }

    /** 两个不相干的问题：后一问不能被前一问吞掉。 */
    @Test
    void twoUnrelatedQuestionsBothHitTheirTargets() {
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), entry(), TWO, TWO, 7L, 12L, null, null);

        assertTrue(routed.decisionBlock().contains("已拆成 2 个问题"), routed.decisionBlock());
        assertTrue(routed.summary().contains("知识问答"), routed.summary());
        assertTrue(routed.summary().contains("制度要点与报告"), routed.summary());
        verify(workflowTargets, times(1)).run(any(), any(), anyString(), anyLong(), anyLong(), anyLong(), any());
        verify(react, atLeastOnce()).execute(any(), anyString(), anyLong(), anyLong(), any());
    }

    /** 同一件事的两块交付仍是一问：只交给覆盖它的方案，不拆、不拉工作流。 */
    @Test
    void oneQuestionWithTwoDeliverablesStaysSingleRoute() {
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), entry(), SINGLE_MULTI, SINGLE_MULTI, 7L, 13L, null, null);

        assertNull(routed.decisionBlock());
        assertTrue(routed.summary().contains("企业综合台"), routed.summary());
        assertFalse(routed.summary().contains("制度要点与报告"), routed.summary());
        verify(workflowTargets, times(0)).run(any(), any(), anyString(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 单问制度咨询：仍由判路由选中的专家处理，入口块只有一条路线。 */
    @Test
    void singleQuestionStaysOnItsExpert() {
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), entry(), SINGLE, SINGLE, 7L, 14L, null, null);

        assertNull(routed.decisionBlock());
        assertTrue(routed.summary().contains("知识问答"), routed.summary());
        verify(workflowTargets, times(0)).run(any(), any(), anyString(), anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 同一句用分号接「另外」点名四个车间：入口要派出四个方案，不能整句只留候选列表里的第一个。
     *
     * <p>候选故意把文书车间排在最前。若切问失败，点名会按「一样长留先出现的名字」只收文书车间。
     * 四个方案都出现，才说明每一问各自点了名。方案内部不配判路由模型，避免这次断言打到真实模型。
     */
    @Test
    void fourWorkshopsInOneSentenceDispatchFourSchemes() {
        long fileExpert = 201L;
        long dataExpert = 202L;
        long auditExpert = 203L;
        long docExpert = 204L;
        long fileScheme = 301L;
        long dataScheme = 302L;
        long auditScheme = 303L;
        long docScheme = 304L;
        when(agents.findById(fileExpert)).thenReturn(Optional.of(agent(fileExpert, "清单专家", "列出操作空间文件")));
        when(agents.findById(dataExpert)).thenReturn(Optional.of(agent(dataExpert, "汇总专家", "按列汇总表格")));
        when(agents.findById(auditExpert)).thenReturn(Optional.of(agent(auditExpert, "扫描专家", "扫描敏感词")));
        when(agents.findById(docExpert)).thenReturn(Optional.of(agent(docExpert, "编号专家", "按前缀生成编号")));

        MaRunSpec file = workshop(fileScheme, "ws_file", "文件车间", fileExpert, "file_inventory");
        MaRunSpec data = workshop(dataScheme, "ws_data", "数据车间", dataExpert, "data_summary");
        MaRunSpec audit = workshop(auditScheme, "ws_audit", "合规车间", auditExpert, "audit_scan");
        MaRunSpec doc = workshop(docScheme, "ws_doc", "文书车间", docExpert, "doc_serial");
        // 文书车间排第一：整句不切开时，点名会只留下它
        MaRunSpec entry = workshopEntry(List.of(
                schemeCandidate(1L, docScheme, "ws_doc", 1),
                schemeCandidate(1L, auditScheme, "ws_audit", 2),
                schemeCandidate(1L, dataScheme, "ws_data", 3),
                schemeCandidate(1L, fileScheme, "ws_file", 4)),
                Map.of(fileScheme, file, dataScheme, data, auditScheme, audit, docScheme, doc));
        String text = "请文件车间整理操作空间并比对两份原稿；另外请数据车间汇总金额并做名单去重；"
                + "另外请合规车间扫描敏感词并推算到期日；另外请文书车间生成编号并套模板出成稿";

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), entry, text, text, 7L, 21L, null, null);

        String decision = routed.decisionBlock();
        assertTrue(decision != null && decision.contains("已拆成 4 个问题"), decision);
        assertTrue(decision.contains("文件车间"), decision);
        assertTrue(decision.contains("数据车间"), decision);
        assertTrue(decision.contains("合规车间"), decision);
        assertTrue(decision.contains("文书车间"), decision);
        int fileAt = decision.indexOf("文件车间");
        int dataAt = decision.indexOf("数据车间");
        int auditAt = decision.indexOf("合规车间");
        int docAt = decision.indexOf("文书车间");
        assertTrue(fileAt >= 0 && fileAt < dataAt && dataAt < auditAt && auditAt < docAt, decision);
        assertTrue(routed.summary().contains("文件车间"), routed.summary());
        assertTrue(routed.summary().contains("数据车间"), routed.summary());
        assertTrue(routed.summary().contains("合规车间"), routed.summary());
        assertTrue(routed.summary().contains("文书车间"), routed.summary());
    }

    /** 闲聊仍走接待助手，不因为入口支持拆问而改变。 */
    @Test
    void greetingStaysWithReception() {
        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent(), entry(), "你好", "你好", 7L, 15L, null, null);

        assertNull(routed.decisionBlock());
        assertTrue(routed.summary().contains("接待助手"), routed.summary());
    }

    /**
     * 块位：决策块一定先出现，子路线的「路由到」由各子 begin 时自己推，且每条只推一次。
     *
     * <p>现网已删除「起子任务之前按工作流优先预推」的做法：预推的无身份行会先占块位，
     * begin 到达后只能原位补身份，卡片就停在预推的位置上。现在块序只认各自 begin 帧，
     * 因此这里不再断言两个子谁先谁后（并行到达决定），只锁「决策块在前」和「没有重复占位」。
     */
    @Test
    void decisionBlockPushedBeforeChildrenOwnRouteLines() {
        // 子路线由并行子线程各自推：必须用线程安全容器，否则 ArrayList 并发写会丢元素
        List<String> routes = java.util.Collections.synchronizedList(new ArrayList<>());
        TrialProgress recorder = new TrialProgress() {
            @Override
            public void phase(String phase) {
                // 本用例只看路由行，阶段变化忽略
            }

            @Override
            public void route(String summary) {
                routes.add(summary);
            }

            @Override
            public void reasoning(String delta) {
                // 思考不属于块位断言范围
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
                // 本用例不产生工具调用
            }

            @Override
            public void assistant(String delta) {
                // 子正文已由各子自己的路由段落承载，这里不关心
            }
        };

        executor.execute(parent(), entry(), BOTH, BOTH, 7L, 16L, recorder, null);

        // 执行返回后子线程都已收口；在锁内做快照，避免读到并发写坏的结构
        List<String> snapshot;
        synchronized (routes) {
            snapshot = new ArrayList<>(routes);
        }
        // 第一条必须是决策块；子路线只能在它之后出现
        assertTrue(snapshot.size() >= 3, snapshot.toString());
        assertTrue(snapshot.get(0).contains("已拆成 2 个问题"), snapshot.get(0));
        // 两问的子路线都必须在决策块之后各推一次：预推会让同一张卡先占位、begin 再补一条
        List<String> childRoutes = snapshot.subList(1, snapshot.size());
        assertEquals(1, childRoutes.stream().filter(line -> line.contains("企业综合台")).count(),
                snapshot.toString());
        assertEquals(1, childRoutes.stream().filter(line -> line.contains("制度要点与报告")).count(),
                snapshot.toString());
    }

    private AgentDef parent() {
        AgentDef agent = new AgentDef();
        agent.setName("Router");
        agent.setRunMode(AgentRunMode.ROUTER.name());
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    /**
     * 入口方案：沿用种子配置（多意图关闭、最多 1 路），证明拆问不受它限制。
     */
    private MaRunSpec entry() {
        AgentRuntimeConfig config = runtime(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER);
        config.setMultiIntentEnabled(false);
        config.setMaxRouteChildren(1);
        MaRunSpec scheme = new MaRunSpec(SCHEME_ID, "enterprise", "企业综合台",
                "制度检索；报告起草", "ROUTER_PARALLEL", runtime("ROUTER_PARALLEL"),
                List.of(agentRow(SCHEME_ID, POLICY_ID, "policy", 1), agentRow(SCHEME_ID, REPORT_ID, "report", 2)),
                null, 99L, false, Map.of(), Map.of());
        return new MaRunSpec(1L, "entry", "Router", "对话入口", AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER,
                config, List.of(
                agentRow(1L, ASSISTANT_ID, "reception_assistant", 1),
                agentRow(1L, KNOWLEDGE_ID, "knowledge", 2),
                schemeRow(),
                workflowRow()),
                null, 99L, true,
                Map.of(SCHEME_ID, scheme),
                Map.of(WORKFLOW_ID, new WorkflowRunSpec(WORKFLOW_ID, "policy_report",
                        "制度要点与报告", "按制度整理要点并出报告", 900L)));
    }

    private static AgentRuntimeConfig runtime(String template) {
        AgentRuntimeConfig config = AgentRuntimeConfig.parse("{}", AgentRunMode.ROUTER.name());
        config.setMaTemplate(template);
        return config;
    }

    private static MaCandidate agentRow(long maId, long agentId, String key, int sortNo) {
        MaCandidate item = new MaCandidate();
        item.setId(agentId + 1000);
        item.setMaId(maId);
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

    /**
     * 四个车间都挂在入口上，多意图关闭、最多 1 路：拆问后仍要四路都在。
     *
     * @param candidates 入口候选（调用方决定顺序）
     * @param targets    方案主键 → 方案执行来源
     * @return 入口方案
     */
    private MaRunSpec workshopEntry(List<MaCandidate> candidates, Map<Long, MaRunSpec> targets) {
        AgentRuntimeConfig config = runtime(AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER);
        config.setMultiIntentEnabled(false);
        config.setMaxRouteChildren(1);
        return new MaRunSpec(1L, "entry", "Router", "对话入口", AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER,
                config, candidates, null, 99L, true, targets, Map.of());
    }

    /**
     * 车间方案：不配判路由模型，内部只有一位专家，跑的时候取第一条，不打模型。
     *
     * @param id      方案主键
     * @param code    方案编码
     * @param name    展示名（点名就认这个）
     * @param expertId 唯一专家
     * @param routeKey 专家 route_key
     * @return 方案执行来源
     */
    private static MaRunSpec workshop(long id, String code, String name, long expertId, String routeKey) {
        return new MaRunSpec(id, code, name, name + "流水线", "ROUTER_PARALLEL",
                runtime("ROUTER_PARALLEL"),
                List.of(agentRow(id, expertId, routeKey, 1)),
                null, null, false, Map.of(), Map.of());
    }

    /** 入口上的一条方案候选。顺序由调用方的列表决定，不在这里重排。 */
    private static MaCandidate schemeCandidate(long entryId, long schemeId, String routeKey, int sortNo) {
        MaCandidate item = new MaCandidate();
        item.setId(schemeId + 5000);
        item.setMaId(entryId);
        item.setTargetType(MaCandidate.TARGET_MA);
        item.setMaDefId(schemeId);
        item.setRouteKey(routeKey);
        item.setSortNo(sortNo);
        item.setEnabled(1);
        return item;
    }

    private static MaCandidate workflowRow() {
        MaCandidate item = new MaCandidate();
        item.setId(9000L);
        item.setMaId(1L);
        item.setTargetType(MaCandidate.TARGET_WORKFLOW);
        item.setWorkflowDefId(WORKFLOW_ID);
        item.setRouteKey("wf_report");
        item.setSortNo(4);
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

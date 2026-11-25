package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固定模板（FIXED_PARALLEL / FIXED_SEQUENCE）与判路由回归的窄真执行测试。所属域：runtime。
 *
 * <p>只 mock 外部协作者（库、装配、ReAct、记忆、限制），跑真实的分支逻辑：
 * 跳过判路由 / 用户原问直发 / 并行上限跳过 / 串行前置摘要 / 失败继续 / ROUTER_PARALLEL 仍判路由。
 * 不起 Spring，不连库，不出站模型。
 */
class FixedTemplateRouterTrialExecutorTest {

    private AgentRouteRepository routes;
    private AgentDefRepository agents;
    private TrialBindingLoader loader;
    private ReActTrialExecutor react;
    private SystemPromptAssembler prompts;
    private RuntimeLimitResolver limitResolver;
    private TrialMemoryCoordinator memory;
    private PlanTrialExecutor planRunner;
    private ChildPlanApprovalRegistry childApprovals;
    private ThreadPoolTaskExecutor pool;
    private RouterTrialExecutor executor;

    private final EffectiveLimits limits = new EffectiveLimits(
            60_000L, 6, 10_000L, 1, 20, 10_000L, 20, 3, 4000);

    @BeforeEach
    void setUp() {
        routes = mock(AgentRouteRepository.class);
        agents = mock(AgentDefRepository.class);
        loader = mock(TrialBindingLoader.class);
        react = mock(ReActTrialExecutor.class);
        prompts = mock(SystemPromptAssembler.class);
        limitResolver = mock(RuntimeLimitResolver.class);
        memory = mock(TrialMemoryCoordinator.class);
        planRunner = mock(PlanTrialExecutor.class);
        childApprovals = new ChildPlanApprovalRegistry();
        pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(3);
        pool.setMaxPoolSize(3);
        pool.setQueueCapacity(8);
        pool.initialize();
        executor = new RouterTrialExecutor(routes, agents, loader, react, prompts, limitResolver, memory,
                planRunner, childApprovals, pool);

        when(limitResolver.resolve(any())).thenReturn(limits);
        // 提示词装配在单测里没有模板，返回空串即可；判路由提示词只拼固定文案
        when(prompts.build(any(), any())).thenReturn("");
        when(memory.recallDurable(any(), any())).thenReturn(new TrialMemoryCoordinator.Augmented("", List.of()));
        TrialPlan plan = new TrialPlan();
        plan.setLimits(limits);
        plan.setNotices(new ArrayList<>());
        when(loader.loadReact(any(), anyString(), any())).thenReturn(plan);
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    /**
     * FIXED_PARALLEL：不判路由、每个子吃用户原问全文、超上限按序跳过并进汇总。
     */
    @Test
    void fixedParallelSkipsJudgeAndFansOutWholeQuestion() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_PARALLEL\",\"answerMode\":\"CONCAT\","
                + "\"maxRouteChildren\":2}");
        givenChildren(parent, "a", "b", "c");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(turn("答"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "帮我分析三个问题", 7L, 21L);

        // 固定模板必须跳过判路由 LLM：一次都不能调用
        verify(react, never()).judge(any(), any(), any(), any());
        verify(loader, never()).findRouterModel(any());

        // 只跑上限内的前 2 个，且送进去的就是用户原问全文（不是切分子问题）
        ArgumentCaptor<String> modelText = ArgumentCaptor.forClass(String.class);
        verify(react, times(2)).execute(any(), modelText.capture(), anyLong(), anyLong(), any());
        assertEquals(List.of("帮我分析三个问题", "帮我分析三个问题"), modelText.getAllValues());

        // 第 3 个标「已跳过」并进终答，不能让用户以为它跑过
        assertTrue(routed.turn().getAssistantText().contains("（已跳过）"),
                routed.turn().getAssistantText());
        assertEquals(3, routed.segments().size());
    }

    /**
     * FIXED_SEQUENCE：串行执行，后一步可见前置终答摘要；前一步失败仍继续。
     */
    @Test
    void fixedSequenceFeedsPriorSummaryAndContinuesOnFailure() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_SEQUENCE\",\"answerMode\":\"CONCAT\","
                + "\"maContextPolicy\":\"PRIOR_SUMMARY_ONLY\"}");
        givenChildren(parent, "a", "b");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("模型炸了"))
                .thenReturn(turn("第二步答案"));

        RouterTrialExecutor.RoutedTurn routed = executor.execute(parent, "用户原问", 7L, 22L);

        verify(react, never()).judge(any(), any(), any(), any());
        ArgumentCaptor<String> modelText = ArgumentCaptor.forClass(String.class);
        verify(react, times(2)).execute(any(), modelText.capture(), anyLong(), anyLong(), any());

        // 第一步只吃用户原问；第二步吃「用户原问 + 前置摘要」，失败步用中文占位
        assertEquals("用户原问", modelText.getAllValues().get(0));
        String second = modelText.getAllValues().get(1);
        assertTrue(second.contains("用户原问"), second);
        assertTrue(second.contains("【前置专家结论】"), second);
        assertTrue(second.contains("（该步未完成）"), second);
        // 失败步的异常原文不能进下一步输入
        assertTrue(!second.contains("模型炸了"), second);

        // 汇总仍按序拼接：失败段可见，成功段保留
        String answer = routed.turn().getAssistantText();
        assertTrue(answer.contains("（执行失败）"), answer);
        assertTrue(answer.contains("第二步答案"), answer);
        assertEquals(2, routed.segments().size());
    }

    /**
     * ROUTER_PARALLEL 回归：仍走判路由 LLM，单意图把 sub_query 交给子。
     */
    @Test
    void routerParallelStillJudges() {
        AgentDef parent = parent("{\"maTemplate\":\"ROUTER_PARALLEL\",\"multiIntentEnabled\":true,"
                + "\"answerMode\":\"CONCAT\"}");
        givenChildren(parent, "a", "b");
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.judge(any(), anyString(), anyString(), any()))
                .thenReturn("[{\"route_key\":\"a\",\"sub_query\":\"子问题A\"}]");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("答A"));

        executor.execute(parent, "用户原问", 7L, 23L);

        verify(react, times(1)).judge(any(), any(), any(), any());
        ArgumentCaptor<String> modelText = ArgumentCaptor.forClass(String.class);
        verify(react, times(1)).execute(any(), modelText.capture(), anyLong(), anyLong(), any());
        assertEquals("子问题A", modelText.getValue());
    }

    /**
     * 零启用候选：固定模板没有判路由兜底，必须中文 FAIL，不能空跑。
     */
    @Test
    void fixedTemplateWithoutCandidatesFailsInChinese() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_SEQUENCE\"}");
        when(routes.listByParentId(1L)).thenReturn(List.of());

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "你好", 7L, 24L));
        assertTrue(failure.getMessage().contains("没有可用的子 Agent"), failure.getMessage());
    }

    /**
     * 子 Agent 必须是启用的 REACT：停用或 ROUTER 子会被过滤掉，等同零候选。
     */
    @Test
    void disabledOrRouterChildIsFiltered() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_PARALLEL\"}");
        AgentRoute routerChild = route("a", 2L);
        when(routes.listByParentId(1L)).thenReturn(List.of(routerChild));
        AgentDef child = agent(2L, "子A", AgentRunMode.ROUTER.name(), AgentStatus.ENABLED);
        when(agents.findById(2L)).thenReturn(Optional.of(child));

        TrialFailure failure = assertThrows(TrialFailure.class,
                () -> executor.execute(parent, "你好", 7L, 25L));
        assertTrue(failure.getMessage().contains("没有可用的子 Agent"), failure.getMessage());
    }

    /**
     * 固定并行：子专家在**直播期**就按身份增量出字，不再等各子结束才整段补。
     *
     * <p>回归点：旧实现（静音子正文）把这个模板下每个子的 {@code assistant} 全部丢弃，
     * 页面表现为「泳道转很久 → 所有子同一拍冒出整段」。改为边生成边推之后，
     * 收尾规则不变：这一位已经流过增量，就不能再把整段答复补一遍（否则是重影）。
     */
    @Test
    void fixedParallelStreamsChildDeltasLiveInsteadOfOnlyAtFinish() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_PARALLEL\",\"answerMode\":\"CONCAT\"}");
        givenChildren(parent, "a", "b");
        AtomicInteger seq = new AtomicInteger();
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    TrialProgress child = invocation.getArgument(4);
                    // 边跑边推：真实流式模型给的就是这种一段一段的增量
                    String mark = "直播" + seq.incrementAndGet();
                    child.assistant(mark);
                    // 收尾再给一份整段答复：已经按增量流过的不得再补一次
                    return turn("整段" + mark);
                });

        List<String> pushes = new ArrayList<>();
        executor.execute(parent, "两家公司制度对比", "两家公司制度对比", 7L, 33L, assistantRecorder(pushes));

        assertTrue(pushes.contains("直播1") && pushes.contains("直播2"),
                "子专家必须直播期增量出字：" + pushes);
        assertFalse(pushes.stream().anyMatch(item -> item.startsWith("整段")),
                "已流过增量的参与者不得再整段补推：" + pushes);
    }

    /**
     * 空结论也要终态：子专家一个字都没产出时，既要有终态说明，也要有 {@code done} 终态帧。
     *
     * <p>回归点：旧实现在 {@code deliverParticipantConclusion} 里遇到空正文直接 return，
     * 于是这张卡既没正文、也没有「这一步结束了」——前端只能等整轮 done，
     * 表现就是「一直转圈，汇总落屏时前面的空壳一起闪现」。
     */
    @Test
    void emptyConclusionStillEmitsTerminalFrameAndNote() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_PARALLEL\",\"answerMode\":\"CONCAT\"}");
        givenChildren(parent, "a", "b");
        // 模型这一路什么都没写出来（非流式空答 / 只有工具步 / 被撞限打断，都会是这样）
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn(""));

        List<String> bodies = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> ends = java.util.Collections.synchronizedList(new ArrayList<>());
        executor.execute(parent, "用户原问", "用户原问", 7L, 34L, terminalRecorder(bodies, ends));

        assertEquals(2, ends.size(), "两位参与者都必须有终态帧：" + ends);
        assertTrue(ends.stream().allMatch(id -> id.endsWith("@" + TrialProgress.PARTICIPANT_DONE)),
                "正常返回的参与者终态是 done：" + ends);
        assertEquals(2, bodies.size(), "空结论也要各补一句终态说明：" + bodies);
        assertTrue(bodies.stream().allMatch(text -> text.contains("未产出正文")), bodies.toString());
    }

    /**
     * SUMMARIZE：参与者先各交一次成文结论，汇总正文再边收边推，聚合器不得把汇总全文再补推一次。
     * <p>回归点：曾经 SUMMARIZE 是「等齐后整段推」，MA 运行页最后一块闪现；
     * 改为流式投递后若末尾仍补推全文，用户会看到「流式正文 + 整段重影」。
     */
    @Test
    void summarizeStreamsDeltasWithoutPushingWholeAnswerAgain() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_PARALLEL\",\"answerMode\":\"SUMMARIZE\"}");
        givenChildren(parent, "a", "b");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("子答"));
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        // 汇总内部把两段增量交给页面，并声明正文已投递
        when(react.summarizeStreamed(any(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    TrialProgress progress = invocation.getArgument(4);
                    progress.assistant("第一段");
                    progress.assistant("第二段");
                    return new ReActTrialExecutor.StreamedAnswer("第一段第二段", true);
                });

        List<String> pushes = new ArrayList<>();
        RouterTrialExecutor.RoutedTurn routed =
                executor.execute(parent, "用户原问", "用户原问", 7L, 31L, assistantRecorder(pushes));

        // 两位参与者先各交一次成文结论，随后才是汇总的两段增量。
        // 末尾不许再补推整段「第一段第二段」，否则就是重影。
        assertEquals(List.of("子答", "子答", "第一段", "第二段"), pushes);
        assertEquals("第一段第二段", routed.turn().getAssistantText());
    }

    /**
     * SUMMARIZE 未投递正文（如流里没有增量）：聚合器必须补推一次整段，
     * 否则页面只在侧栏显示「终答汇总中…」，正文永远空白。参与者结论仍各交一次。
     */
    @Test
    void summarizeWithoutDeliveryPushesWholeAnswerOnce() {
        AgentDef parent = parent("{\"maTemplate\":\"FIXED_PARALLEL\",\"answerMode\":\"SUMMARIZE\"}");
        givenChildren(parent, "a", "b");
        when(react.execute(any(), anyString(), anyLong(), anyLong(), any())).thenReturn(turn("子答"));
        when(loader.findRouterModel(1L)).thenReturn(Optional.of(9L));
        when(react.summarizeStreamed(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new ReActTrialExecutor.StreamedAnswer("汇总稿", false));

        List<String> pushes = new ArrayList<>();
        RouterTrialExecutor.RoutedTurn routed =
                executor.execute(parent, "用户原问", "用户原问", 7L, 32L, assistantRecorder(pushes));

        // 参与者结论先按人交出；汇总没流式投递时只补推一次整段，不能再叠第二遍
        assertEquals(List.of("子答", "子答", "汇总稿"), pushes);
        assertEquals("汇总稿", routed.turn().getAssistantText());
    }

    /**
     * 只收正文增量的录制器：其余回调都是空实现，便于直接断言「推了几次正文」。
     *
     * @param pushes 正文增量按到达顺序追加
     * @return 可传给 execute 的进度出口
     */
    private static TrialProgress assistantRecorder(List<String> pushes) {
        return new TrialProgress() {
            @Override
            public void phase(String phase) {
                // 状态机不是本用例关注点
            }

            @Override
            public void route(String summary) {
                // 路由摘要不参与断言
            }

            @Override
            public void reasoning(String delta) {
                // 汇总不产出思考
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
                // 汇总不调工具
            }

            @Override
            public synchronized void assistant(String delta) {
                // 并行子在池线程上各交一次结论，录制器必须串行追加，否则 ArrayList 会丢元素
                pushes.add(delta);
            }
        };
    }

    /**
     * 只收「正文」与「参与者终态」的录制器，便于断言空结论也收口。
     *
     * <p>子专家在池线程上并发回调，两个列表都必须同步容器，否则断言会漏帧。
     *
     * @param bodies 正文增量（含空结论补出的终态说明）
     * @param ends   参与者终态：{@code participantId@state}
     * @return 可传给 execute 的进度出口
     */
    private static TrialProgress terminalRecorder(List<String> bodies, List<String> ends) {
        return new TrialProgress() {
            @Override
            public void phase(String phase) {
                // 状态机不是本用例关注点
            }

            @Override
            public void route(String summary) {
                // 路由摘要不参与断言
            }

            @Override
            public void reasoning(String delta) {
                // 思考不参与断言
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
                // 本用例不打工具
            }

            @Override
            public void assistant(String delta) {
                if (delta != null && !delta.isBlank()) {
                    bodies.add(delta);
                }
            }

            @Override
            public void endParticipant(com.agenthub.runtime.domain.model.ParticipantView participant,
                                       String state, String note) {
                ends.add((participant == null ? "?" : participant.id()) + "@" + state);
            }
        };
    }

    /** 造父 Agent（ROUTER + 指定 runtime_config）。 */    private AgentDef parent(String runtimeJson) {
        return agent(1L, "父路由器", AgentRunMode.ROUTER.name(), AgentStatus.ENABLED, runtimeJson);
    }

    /** 造父 + N 个启用的 REACT 子，并挂好 route_key=a/b/c…。 */
    private void givenChildren(AgentDef parent, String... keys) {
        List<AgentRoute> rows = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            long childId = 10L + i;
            rows.add(route(keys[i], childId));
            when(agents.findById(childId))
                    .thenReturn(Optional.of(agent(childId, "子" + keys[i].toUpperCase(), AgentRunMode.REACT.name(),
                            AgentStatus.ENABLED)));
        }
        when(routes.listByParentId(parent.getId())).thenReturn(rows);
    }

    private AgentRoute route(String key, Long childId) {
        AgentRoute route = new AgentRoute();
        route.setId(childId + 100);
        route.setParentAgentId(1L);
        route.setChildAgentId(childId);
        route.setRouteKey(key);
        route.setIntent("意图" + key);
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    private AgentDef agent(Long id, String name, String runMode, int status) {
        return agent(id, name, runMode, status, null);
    }

    private AgentDef agent(Long id, String name, String runMode, int status, String runtimeJson) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName(name);
        agent.setCode(name);
        agent.setRunMode(runMode);
        agent.setStatus(status);
        agent.setRuntimeConfigJson(runtimeJson);
        return agent;
    }

    private TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}

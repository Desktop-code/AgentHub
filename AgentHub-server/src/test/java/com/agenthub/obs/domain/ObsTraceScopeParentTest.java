package com.agenthub.obs.domain;

import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanDraft;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.model.TraceStart;
import com.agenthub.runtime.application.router.ChildRunner;
import com.agenthub.runtime.application.router.MaNestedContext;
import com.agenthub.runtime.application.router.RouteSpan;
import com.agenthub.runtime.application.router.RouterObs;
import com.agenthub.workflow.infra.WorkflowConsoleAgentSpan;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Router 合问挂点：子 AGENT parent = 判路由 LLM，≠ ROUTE/根；子内链仍挂本 AGENT。
 * 所属域：obs。
 */
class ObsTraceScopeParentTest {

    /**
     * 模拟 根 → 判路由 LLM →（子 AGENT∥子 AGENT）→ 子内；只验 parent_id / step_no，不动 seq 规则。
     */
    @Test
    void multiRouteChildrenParentIsJudgeLlmNotRoute() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-合问", recorder);

        Long route = trace.open(SpanType.ROUTE, "judge", 1L, null, null);
        trace.bindParent(route);
        Long judgeLlm = trace.open(SpanType.LLM, "judge-model", 1L, trace.implicitParent(), null);
        assertEquals(judgeLlm, trace.lastLlmSpanId());
        assertEquals(judgeLlm, trace.llmUnder(route));

        // 扇出前锁定挂点：只认挂在本层 ROUTE 下的 LLM
        Long childParent = ChildRunner.childAgentParent(trace, route);
        assertEquals(judgeLlm, childParent);
        assertNotEquals(route, childParent);

        Long agentA = trace.open(SpanType.AGENT, "A", 11L, childParent, "q-a", true);
        Long agentB = trace.open(SpanType.AGENT, "B", 12L, childParent, "q-b", true);
        assertEquals(judgeLlm, recorder.parentOf(agentA));
        assertEquals(judgeLlm, recorder.parentOf(agentB));
        assertEquals("3.1", recorder.stepNoOf(agentA));
        assertEquals("3.2", recorder.stepNoOf(agentB));

        // 子内串行链：AGENT → LLM → TOOL → LLM，禁止三子全挂本 AGENT
        trace.bindParent(agentA);
        Long llm1 = trace.open(SpanType.LLM, "m1", 11L, trace.implicitParent(), null);
        Long tool = trace.open(SpanType.TOOL, "tool", 11L, trace.implicitParent(), null);
        Long llm2 = trace.open(SpanType.LLM, "m2", 11L, trace.implicitParent(), null);
        assertEquals(agentA, recorder.parentOf(llm1));
        assertEquals(llm1, recorder.parentOf(tool));
        assertEquals(tool, recorder.parentOf(llm2));
        assertEquals("3.1.1", recorder.stepNoOf(llm1));
        assertEquals("3.1.1.1", recorder.stepNoOf(tool));
        assertEquals("3.1.1.1.1", recorder.stepNoOf(llm2));

        assertEquals(route, recorder.parentOf(judgeLlm));
        assertNull(recorder.parentOf(route));
        assertEquals(1, recorder.seqOf(route));
        assertEquals(2, recorder.seqOf(judgeLlm));
        assertEquals(3, recorder.seqOf(agentA));
        assertEquals(4, recorder.seqOf(agentB));
    }

    /**
     * 单路由 ReAct：根 AGENT 下串行成链，禁止 LLM/TOOL 全挂根。
     */
    @Test
    void singleRouteReactSerialParentChain() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-react", recorder);

        Long agent = trace.open(SpanType.AGENT, "退货客服评测", 1L, null, null);
        trace.bindParent(agent);
        Long llm1 = trace.open(SpanType.LLM, "deepseek-flash", 1L, trace.implicitParent(), null);
        Long tool = trace.open(SpanType.TOOL, "searchKnowledge", 1L, trace.implicitParent(), null);
        Long llm2 = trace.open(SpanType.LLM, "deepseek-flash", 1L, trace.implicitParent(), null);

        assertNull(recorder.parentOf(agent));
        assertEquals(agent, recorder.parentOf(llm1));
        assertEquals(llm1, recorder.parentOf(tool));
        assertEquals(tool, recorder.parentOf(llm2));
        assertEquals("1", recorder.stepNoOf(agent));
        assertEquals("2", recorder.stepNoOf(llm1));
        assertEquals("3", recorder.stepNoOf(tool));
        assertEquals("4", recorder.stepNoOf(llm2));
        assertEquals(1, recorder.seqOf(agent));
        assertEquals(2, recorder.seqOf(llm1));
        assertEquals(3, recorder.seqOf(tool));
        assertEquals(4, recorder.seqOf(llm2));
    }

    /**
     * 同父并行扇出不推进叶：两子仍同挂分叉点，禁串成假链。
     */
    @Test
    void parallelFanoutKeepsSharedParent() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-fan", recorder);
        Long fork = trace.open(SpanType.LLM, "fork", 1L, null, null);
        Long a = trace.open(SpanType.TOOL, "a", 1L, fork, null, true);
        Long b = trace.open(SpanType.TOOL, "b", 1L, fork, null, true);
        assertEquals(fork, recorder.parentOf(a));
        assertEquals(fork, recorder.parentOf(b));
        // 扇出未推进叶；下一串行步仍挂 fork（分叉点），而非误链到 a
        Long after = trace.open(SpanType.LLM, "after", 1L, trace.implicitParent(), null);
        assertEquals(fork, recorder.parentOf(after));
    }

    /**
     * 单路由：唯一子 AGENT 同样 parent = 判路由 LLM。
     */
    @Test
    void singleRouteChildParentIsJudgeLlm() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-单", recorder);
        Long route = trace.open(SpanType.ROUTE, "judge", 1L, null, null);
        trace.bindParent(route);
        Long judgeLlm = trace.open(SpanType.LLM, "judge-model", 1L, route, null);
        Long childParent = ChildRunner.childAgentParent(trace, route);
        Long agent = trace.open(SpanType.AGENT, "only", 11L, childParent, "q", false);
        assertEquals(judgeLlm, recorder.parentOf(agent));
        assertNotEquals(route, recorder.parentOf(agent));
        assertEquals("3", recorder.stepNoOf(agent));
    }

    /**
     * 顶层方案根和 judge 父都为空；只有嵌套时方案根挂外壳、judge 挂内层方案根。
     */
    @Test
    void nestedSchemeRootAndJudgeHangOnShellTopLevelStayNull() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-nest", recorder);

        Long topRoot = trace.open(SpanType.AGENT, "单方案", null, null, null);
        trace.bindParent(topRoot);
        RouteSpan topJudge = RouteSpan.open(trace, "judge", null);
        assertNull(recorder.parentOf(topJudge.id()));
        assertNull(recorder.parentOf(topRoot));

        trace.bindParent(null);
        Long shell = trace.open(SpanType.AGENT, "综合台", null, null, null);
        trace.bindParent(shell);
        Long innerRoot = trace.open(SpanType.AGENT, "综合台", null, shell, null);
        trace.bindParent(innerRoot);
        MaNestedContext.enter();
        try {
            RouteSpan innerJudge = RouteSpan.open(trace, "judge", null);
            assertEquals(shell, recorder.parentOf(innerRoot));
            assertEquals(innerRoot, recorder.parentOf(innerJudge.id()));
            assertNull(recorder.parentOf(shell));
        } finally {
            MaNestedContext.exit();
            assertFalse(MaNestedContext.inside());
        }
    }

    /**
     * 入口智能网关：judge 挂到网关根上，网关根本身父仍空。开关关掉后普通单方案 judge 恢复父空。
     */
    @Test
    void entryGatewayJudgeHangsOnGatewayRoot() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-gw", recorder);
        Long gateway = trace.open(SpanType.AGENT, "智能网关", null, null, null);
        trace.bindParent(gateway);
        boolean previous = RouteSpan.judgeHangsOnRoot();
        RouteSpan.judgeHangsOnRoot(true);
        try {
            RouteSpan judge = RouteSpan.open(trace, "judge", null);
            assertEquals(gateway, recorder.parentOf(judge.id()));
            assertNull(recorder.parentOf(gateway));
        } finally {
            RouteSpan.judgeHangsOnRoot(previous);
        }
        trace.bindParent(gateway);
        RouteSpan plain = RouteSpan.open(trace, "judge", null);
        assertNull(recorder.parentOf(plain.id()));
        assertFalse(RouteSpan.judgeHangsOnRoot());
    }

    /**
     * CONCAT 不另开模型，但仍在本层 ROUTE 下留下拼接节点。
     */
    @Test
    void concatSummaryHangsOnRouteWithoutLlm() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-concat", recorder);
        Long route = trace.open(SpanType.ROUTE, "judge", null, null, null);
        RouterObs.noteConcat(trace, route);
        Long concat = recorder.lastId();
        assertEquals(SpanType.SYSTEM, recorder.typeOf(concat));
        assertEquals(route, recorder.parentOf(concat));
        assertTrue(recorder.attrsOf(concat).contains("obs.summary"));
        assertTrue(recorder.attrsOf(concat).contains("concat"));
    }

    /**
     * 对话 trace 已绑定时，工作流子 Agent 挂在外壳下，收口后栈父回到外壳。
     */
    @Test
    void workflowChildAgentHangsOnConsoleShell() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-wf", recorder);
        Long shell = trace.open(SpanType.AGENT, "例会纪要", null, null, null);
        trace.bindParent(shell);
        WorkflowConsoleAgentSpan.Handle mark = WorkflowConsoleAgentSpan.open(trace, "纪要专家", 9L, "这周例会");
        Long child = recorder.lastId();
        assertEquals(shell, recorder.parentOf(child));
        assertEquals(SpanType.AGENT, recorder.typeOf(child));
        assertEquals(child, trace.implicitParent());
        mark.ok();
        assertEquals(shell, trace.implicitParent());
        assertTrue(recorder.attrsOf(child).contains("执行子 Agent"));
        assertFalse(recorder.attrsOf(child).contains("obs.role"));
    }

    /**
     * 内层没开模型步时，专家挂本层 ROUTE，不挂入口 judge，也不挂并行工作流刚写上的 LLM。
     */
    @Test
    void innerExpertIgnoresOuterAndForeignLlm() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-layer", recorder);
        Long outerRoute = trace.open(SpanType.ROUTE, "judge", 1L, null, null);
        trace.bindParent(outerRoute);
        Long outerLlm = trace.open(SpanType.LLM, "entry-judge", 1L, outerRoute, null);

        Long shell = trace.open(SpanType.AGENT, "工作流", null, outerLlm, null);
        Long foreignLlm = trace.open(SpanType.LLM, "workflow-model", null, shell, null);
        assertEquals(foreignLlm, trace.lastLlmSpanId());

        Long innerRoute = trace.open(SpanType.ROUTE, "judge", null, null, null);
        assertEquals(innerRoute, ChildRunner.childAgentParent(trace, innerRoute));
        assertEquals(outerLlm, ChildRunner.childAgentParent(trace, outerRoute));
        assertNotEquals(foreignLlm, ChildRunner.childAgentParent(trace, outerRoute));
    }

    /**
     * 进入目标方案前清掉全局模型步，出来后写回；按父索引的两条 LLM 都还在。
     */
    @Test
    void llmCursorRestoresOuterWithoutDroppingInner() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope trace = new ObsTraceScope("t-cursor", recorder);
        Long outerRoute = trace.open(SpanType.ROUTE, "judge", 1L, null, null);
        trace.bindParent(outerRoute);
        Long outerLlm = trace.open(SpanType.LLM, "entry-judge", 1L, outerRoute, null);
        ObsTraceScope.LlmCursor saved = trace.captureLlmCursor();
        assertEquals(outerLlm, saved.spanId());

        trace.restoreLlmCursor(null);
        assertNull(trace.lastLlmSpanId());
        Long innerRoute = trace.open(SpanType.ROUTE, "judge", null, null, null);
        trace.bindParent(innerRoute);
        Long innerLlm = trace.open(SpanType.LLM, "inner-judge", null, innerRoute, null);
        assertEquals(innerLlm, ChildRunner.childAgentParent(trace, innerRoute));

        trace.restoreLlmCursor(saved);
        assertEquals(outerLlm, trace.lastLlmSpanId());
        assertEquals(outerLlm, trace.llmUnder(outerRoute));
        assertEquals(innerLlm, trace.llmUnder(innerRoute));
        assertEquals(outerLlm, ChildRunner.childAgentParent(trace, outerRoute));
    }

    /**
     * 真专家不打种类、文案不变；方案 / 工作流外壳才写 obs.role，且不冲掉模板已有字段。
     */
    @Test
    void shellRoleLeavesExpertBriefUntouched() {
        assertEquals("执行子 Agent", RouterObs.shellBrief(false, false));
        assertEquals("执行方案", RouterObs.shellBrief(true, false));
        assertEquals("执行工作流", RouterObs.shellBrief(false, true));
        assertNull(RouterObs.withShellRole(null, false, false));
        Map<String, Object> merged = RouterObs.withShellRole(Map.of("ma.role", "executor"), false, true);
        assertEquals(RouterObs.ROLE_WORKFLOW, merged.get(RouterObs.OBS_ROLE));
        assertEquals("executor", merged.get("ma.role"));
        assertEquals(RouterObs.ROLE_SCHEME, RouterObs.shellRoleFields(true, false).get(RouterObs.OBS_ROLE));
        assertEquals(0, RouterObs.shellRoleFields(false, false).size());
    }

    /**
     * 内存 TraceRecorder：只记 id/parent/step_no/seq，供挂点单测。
     */
    private static final class RecordingRecorder implements TraceRecorder {
        private final AtomicLong ids = new AtomicLong(470);
        private final ConcurrentHashMap<Long, Long> parent = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, String> stepNo = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, Integer> seq = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, SpanType> types = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, String> attrs = new ConcurrentHashMap<>();
        private volatile long lastId;

        @Override
        public Optional<ObsTraceScope> beginConsole(TraceStart start) {
            return Optional.empty();
        }

        @Override
        public Long openSpan(ObsTraceScope scope, SpanDraft draft) {
            long id = ids.getAndIncrement();
            lastId = id;
            types.put(id, draft.type());
            if (draft.parentId() != null) {
                parent.put(id, draft.parentId());
            }
            String parentStepNo = draft.parentId() == null ? null : stepNo.get(draft.parentId());
            String no = scope.allocateStepNo(draft.parentId(), parentStepNo, draft.parallelFanout());
            stepNo.put(id, no);
            seq.put(id, scope.allocateSeq());
            scope.registerStepNo(id, no);
            return id;
        }

        @Override
        public void closeSpan(ObsTraceScope scope, Long spanId, SpanClose close) {
            if (spanId != null && close != null && close.getAttrsJson() != null) {
                attrs.put(spanId, close.getAttrsJson());
            }
        }

        @Override
        public void finish(ObsTraceScope scope, ObsStatus status, String errorSummary) {
            // no-op
        }

        @Override
        public void cancelSession(long sessionId, String reason) {
            // no-op
        }

        Long parentOf(Long id) {
            return parent.get(id);
        }

        String stepNoOf(Long id) {
            return stepNo.get(id);
        }

        int seqOf(Long id) {
            return seq.get(id);
        }

        Long lastId() {
            return lastId;
        }

        SpanType typeOf(Long id) {
            return types.get(id);
        }

        String attrsOf(Long id) {
            return attrs.getOrDefault(id, "");
        }
    }
}

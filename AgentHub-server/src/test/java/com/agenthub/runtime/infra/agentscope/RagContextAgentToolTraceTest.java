package com.agenthub.runtime.infra.agentscope;

import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.TraceRecorder;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanDraft;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.model.TraceStart;
import com.agenthub.rag.application.RagObsSpans;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具线程的 Trace 锚点。所属域：runtime 的 infra。
 * <p>
 * 复现线上场景：{@link RagContextAgentTool} 在 ReAct 主线程组装（trace 已 bind），
 * 工具体在 {@code boundedElastic} 执行（那里 {@link ObsTraceHolder} 取不到）。
 * 断言：
 * <ol>
 *   <li>换线程后锚点仍能锚到本次 TOOL span，检索子步能落库；</li>
 *   <li>按 toolCallId 精确匹配，并行调用不会挂到别的 TOOL 上；</li>
 *   <li>callId 未登记时保持空操作，不写子步也不抛错。</li>
 * </ol>
 */
class RagContextAgentToolTraceTest {

    @AfterEach
    void tearDown() {
        ObsTraceHolder.clear();
        RagObsSpans.currentOrInactive().end();
    }

    /**
     * 主线程组装、工具线程执行：子步必须挂到本次 TOOL span。
     */
    @Test
    void anchorsToolSpanAcrossSchedulerThread() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope scope = new ObsTraceScope("trace-hop", recorder);
        ObsTraceHolder.bind(scope);
        Long toolSpanId = scope.open(SpanType.TOOL, "searchKnowledgeBase", 7L, null, null);
        scope.registerToolSpan("call-1", toolSpanId);

        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicBoolean active = new AtomicBoolean(false);
        AgentTool delegate = writingTool(() -> {
            threadName.set(Thread.currentThread().getName());
            RagObsSpans.Session session = RagObsSpans.begin(7L);
            active.set(session.isActive());
            session.step(RagObsSpans.TOOL_INPUT, 1L, "退货流程", 5, "query: 退货流程").ok("{}", Map.of());
            session.end();
            return "ok";
        });

        // 关键：构造在主线程（trace 可见），执行换线程（trace 不可见）
        RagContextAgentTool wrapper = new RagContextAgentTool(delegate, 7L);
        ObsTraceHolder.clear();

        wrapper.callAsync(param("call-1")).subscribeOn(Schedulers.boundedElastic())
                .block(Duration.ofSeconds(5));

        assertTrue(active.get(), "工具线程应能拿到 TOOL 锚点");
        assertFalse(Thread.currentThread().getName().equals(threadName.get()), "工具体必须换线程执行");
        SpanDraft child = onlyRetrieve(recorder);
        assertEquals(RagObsSpans.TOOL_INPUT, child.name());
        assertEquals(toolSpanId, child.parentId(), "子步必须挂本次 TOOL span");
    }

    /**
     * 同批并行调用：各自的 callId 只能锚到自己的 TOOL span，禁止回退「最近一条」。
     */
    @Test
    void matchesToolSpanByCallIdOnly() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope scope = new ObsTraceScope("trace-parallel", recorder);
        ObsTraceHolder.bind(scope);
        Long first = scope.open(SpanType.TOOL, "searchKnowledgeBase", 7L, null, null);
        Long second = scope.open(SpanType.TOOL, "searchKnowledgeBase", 7L, null, null);
        scope.registerToolSpan("call-1", first);
        scope.registerToolSpan("call-2", second);

        AgentTool delegate = writingTool(() -> {
            RagObsSpans.Session session = RagObsSpans.begin(7L);
            session.step(RagObsSpans.TOOL_INPUT, 1L, "q2", 5, "query: q2").ok("{}", Map.of());
            session.end();
            return "ok";
        });
        RagContextAgentTool wrapper = new RagContextAgentTool(delegate, 7L);
        ObsTraceHolder.clear();

        wrapper.callAsync(param("call-2")).subscribeOn(Schedulers.boundedElastic())
                .block(Duration.ofSeconds(5));

        assertEquals(second, onlyRetrieve(recorder).parentId(), "必须挂第二次调用，不能挂最近一条");
    }

    /**
     * callId 未登记（事件还没到 / id 不一致）：保持空操作，并且不写子步。
     */
    @Test
    void staysInactiveWhenCallIdUnknown() {
        RecordingRecorder recorder = new RecordingRecorder();
        ObsTraceScope scope = new ObsTraceScope("trace-miss", recorder);
        ObsTraceHolder.bind(scope);
        Long toolSpanId = scope.open(SpanType.TOOL, "searchKnowledgeBase", 7L, null, null);
        scope.registerToolSpan("call-1", toolSpanId);

        AtomicBoolean active = new AtomicBoolean(true);
        AgentTool delegate = writingTool(() -> {
            RagObsSpans.Session session = RagObsSpans.begin(7L);
            active.set(session.isActive());
            session.end();
            return "ok";
        });
        RagContextAgentTool wrapper = new RagContextAgentTool(delegate, 7L);
        ObsTraceHolder.clear();

        wrapper.callAsync(param("call-404")).subscribeOn(Schedulers.boundedElastic())
                .block(Duration.ofSeconds(5));

        assertFalse(active.get(), "没锚到时必须空操作，不能退回最近一条 TOOL");
        assertTrue(recorder.drafts.stream().noneMatch(d -> d.type() == SpanType.RETRIEVE));
    }

    /**
     * 造一个只写一次检索子步的假工具，模拟 searchKnowledgeBase 的埋点位置。
     */
    private AgentTool writingTool(ToolBody body) {
        return new StubTool(body);
    }

    private List<SpanDraft> retrieves(RecordingRecorder recorder) {
        List<SpanDraft> out = new ArrayList<>();
        for (SpanDraft draft : recorder.drafts) {
            if (draft.type() == SpanType.RETRIEVE) {
                out.add(draft);
            }
        }
        return out;
    }

    private SpanDraft onlyRetrieve(RecordingRecorder recorder) {
        List<SpanDraft> children = retrieves(recorder);
        assertEquals(1, children.size(), "应只写一条检索子步");
        return children.get(0);
    }

    private ToolCallParam param(String callId) {
        return ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock(callId, "searchKnowledgeBase", Map.of("query", "q")))
                .input(Map.of("query", "q"))
                .build();
    }

    /** 工具体：返回固定文本，供断言线程与埋点。 */
    @FunctionalInterface
    private interface ToolBody {
        String run();
    }

    /**
     * 最小 AgentTool 实现：只关心 callAsync 在哪个线程跑，不参与真实工具装配。
     */
    private static final class StubTool implements AgentTool {

        private final ToolBody body;

        private StubTool(ToolBody body) {
            this.body = body;
        }

        @Override
        public String getName() {
            return "searchKnowledgeBase";
        }

        @Override
        public String getDescription() {
            return "测试用检索工具";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object");
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            // 与 ReflectiveFunctionTool 一致：Mono.fromCallable，在订阅线程执行方法体
            return Mono.fromCallable(() -> {
                body.run();
                return ToolResultBlock.text("ok");
            });
        }
    }

    /**
     * 内存 recorder：只记录开放/收尾，供断言父子与顺序。
     */
    private static final class RecordingRecorder implements TraceRecorder {

        private final List<SpanDraft> drafts = new ArrayList<>();
        private final List<Long> ids = new ArrayList<>();
        private final Map<Long, SpanClose> closes = new LinkedHashMap<>();
        private long serial = 100L;

        @Override
        public Optional<ObsTraceScope> beginConsole(TraceStart start) {
            return Optional.empty();
        }

        @Override
        public Long openSpan(ObsTraceScope scope, SpanDraft draft) {
            long id = ++serial;
            drafts.add(draft);
            ids.add(id);
            return id;
        }

        @Override
        public void closeSpan(ObsTraceScope scope, Long spanId, SpanClose close) {
            closes.put(spanId, close);
        }

        @Override
        public void finish(ObsTraceScope scope, ObsStatus status, String errorSummary) {
        }

        @Override
        public void cancelSession(long sessionId, String reason) {
        }
    }
}

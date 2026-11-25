package com.agenthub.workflow.infra;

import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.TraceRecorder;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanDraft;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.model.TraceStart;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流埋点实现单测。所属域：workflow 的 infra。
 *
 * <p>只验「工作流语义 → obs 语义」的翻译：根 trace 的 channel / workflow_run_id / attrs、
 * 节点步的类型与入出参、终态映射，以及写入实现抛异常时的降级。全程内存替身，不出站、不连库。
 */
class WorkflowObsTraceWriterTest {

    private RecordingRecorder recorder;
    private WorkflowRunRepository runs;
    private WorkflowObsTraceWriter writer;
    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        recorder = new RecordingRecorder();
        runs = mock(WorkflowRunRepository.class);
        WorkflowDefRepository defs = mock(WorkflowDefRepository.class);
        WorkflowDef def = new WorkflowDef();
        def.setId(5L);
        def.setCode("leave_approve");
        def.setName("请假审批");
        when(defs.findById(5L)).thenReturn(Optional.of(def));

        writer = new WorkflowObsTraceWriter(recorder, defs, runs);
        run = new WorkflowRun();
        run.setId(12L);
        run.setWorkflowId(5L);
        run.setReleaseId(7L);
        run.setReleaseVersion(2);
        run.setTriggeredBy(9L);
        run.setStatus(WorkflowRunStatus.CREATED.name());
    }

    @Test
    @DisplayName("开跑：channel=WORKFLOW、workflow_run_id=runId、attrs 带工作流名，并回写 trace_id")
    void beginRunWritesWorkflowTrace() {
        writer.beginRun(run);

        assertThat(run.getTraceId()).isNotBlank();
        assertThat(recorder.workflowRunId).isEqualTo("12");
        assertThat(recorder.sessionId).isEqualTo(0L);
        assertThat(recorder.userId).isEqualTo(9L);
        assertThat(recorder.title).contains("请假审批").contains("v2");
        assertThat(recorder.attrsJson)
                .contains("\"workflow_name\":\"请假审批\"")
                .contains("\"workflow_def_id\":5")
                .contains("\"release_version\":2");
        verify(runs).attachTraceId(12L, run.getTraceId());
    }

    @Test
    @DisplayName("节点：开 TASK 步，收口带状态 / 入出参摘要 / nodeType")
    void nodeSpanCarriesStatusAndSummaries() {
        writer.beginRun(run);
        WorkflowNode node = node("answer", "AGENT");

        writer.beginNode(run, node);
        assertThat(recorder.openedTypes).containsExactly(SpanType.TASK);
        assertThat(recorder.openedNames).containsExactly("answer · AGENT");

        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setNodeId("n2");
        row.setNodeCode("answer");
        row.setNodeType("AGENT");
        row.setAttempt(1);
        row.setStatus(WorkflowNodeRunStatus.SUCCESS.name());
        row.setInputJson("{\"query\":\"年假还剩几天\"}");
        row.setOutputJson("{\"text\":\"还剩 3 天\"}");
        writer.endNode(run, node, row);

        assertThat(recorder.closes).hasSize(1);
        SpanClose close = recorder.closes.get(0);
        assertThat(close.getStatus()).isEqualTo(ObsStatus.OK);
        assertThat(close.getInputText()).contains("年假还剩几天");
        assertThat(close.getOutputText()).contains("还剩 3 天");
        assertThat(close.getAttrsJson()).contains("\"node_type\":\"AGENT\"").contains("\"node_code\":\"answer\"");
    }

    @Test
    @DisplayName("节点失败：span 收 ERROR 并带失败摘要")
    void failedNodeClosesAsError() {
        writer.beginRun(run);
        WorkflowNode node = node("answer", "AGENT");
        writer.beginNode(run, node);
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setNodeId("n2");
        row.setNodeCode("answer");
        row.setStatus(WorkflowNodeRunStatus.FAILED.name());
        row.setErrorSummary("模型超时");

        writer.endNode(run, node, row);

        assertThat(recorder.closes.get(0).getStatus()).isEqualTo(ObsStatus.ERROR);
        assertThat(recorder.closes.get(0).getErrorSummary()).contains("模型超时");
    }

    @Test
    @DisplayName("人工卡点：WAITING_HUMAN 不收口节点，PAUSED_HUMAN 不收口根 trace")
    void humanPauseKeepsTraceRunning() {
        writer.beginRun(run);
        WorkflowNode node = node("review", "HUMAN");
        writer.beginNode(run, node);
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setNodeId("n3");
        row.setNodeCode("review");
        row.setStatus(WorkflowNodeRunStatus.WAITING_HUMAN.name());

        writer.endNode(run, node, row);
        run.setStatus(WorkflowRunStatus.PAUSED_HUMAN.name());
        writer.finishRun(run);

        assertThat(recorder.closes).isEmpty();
        assertThat(recorder.finishedStatus).isNull();
    }

    @Test
    @DisplayName("终态：SUCCESS→OK、FAILED→ERROR，取消走 CANCELLED")
    void finishMapsRunStatus() {
        writer.beginRun(run);
        run.setStatus(WorkflowRunStatus.SUCCESS.name());
        writer.finishRun(run);
        assertThat(recorder.finishedStatus).isEqualTo(ObsStatus.OK);

        WorkflowRun failed = new WorkflowRun();
        failed.setId(13L);
        failed.setWorkflowId(5L);
        failed.setTriggeredBy(9L);
        writer.beginRun(failed);
        failed.setStatus(WorkflowRunStatus.FAILED.name());
        failed.setErrorSummary("节点失败");
        writer.finishRun(failed);
        assertThat(recorder.finishedStatus).isEqualTo(ObsStatus.ERROR);
        assertThat(recorder.finishedError).contains("节点失败");
    }

    @Test
    @DisplayName("降级：写入实现全程抛异常，埋点方法不向外抛，run 状态不受影响")
    void recorderFailureIsSwallowed() {
        WorkflowObsTraceWriter broken = new WorkflowObsTraceWriter(
                new ThrowingRecorder(), mock(WorkflowDefRepository.class), mock(WorkflowRunRepository.class));
        WorkflowNode node = node("answer", "AGENT");
        WorkflowNodeRun row = new WorkflowNodeRun();
        row.setNodeId("n2");
        row.setStatus(WorkflowNodeRunStatus.SUCCESS.name());

        assertThatCode(() -> {
            broken.beginRun(run);
            broken.beginNode(run, node);
            broken.endNode(run, node, row);
            run.setStatus(WorkflowRunStatus.SUCCESS.name());
            broken.finishRun(run);
        }).doesNotThrowAnyException();
        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
    }

    private WorkflowNode node(String code, String type) {
        WorkflowNode node = new WorkflowNode();
        node.setId("n-" + code);
        node.setCode(code);
        node.setType(type);
        return node;
    }

    /**
     * 内存写入实现：只记调用参数，不落库。
     */
    private static final class RecordingRecorder implements TraceRecorder {

        private final AtomicLong ids = new AtomicLong(900);
        private final List<SpanClose> closes = new ArrayList<>();
        private final List<SpanType> openedTypes = new ArrayList<>();
        private final List<String> openedNames = new ArrayList<>();
        private final List<String> knownTraces = new ArrayList<>();
        private String workflowRunId;
        private Long sessionId;
        private Long userId;
        private String title;
        private String attrsJson;
        private ObsStatus finishedStatus;
        private String finishedError;

        @Override
        public Optional<ObsTraceScope> beginConsole(TraceStart start) {
            return Optional.empty();
        }

        @Override
        public Optional<ObsTraceScope> beginWorkflow(TraceStart start, String workflowRunId, String attrsJson) {
            this.sessionId = start.sessionId();
            this.userId = start.userId();
            this.title = start.title();
            this.workflowRunId = workflowRunId;
            this.attrsJson = attrsJson;
            String traceId = "trace-" + workflowRunId;
            knownTraces.add(traceId);
            return Optional.of(new ObsTraceScope(traceId, this));
        }

        @Override
        public Optional<ObsTraceScope> resumeWorkflow(String traceId) {
            return knownTraces.contains(traceId)
                    ? Optional.of(new ObsTraceScope(traceId, this)) : Optional.empty();
        }

        @Override
        public Long openSpan(ObsTraceScope scope, SpanDraft draft) {
            openedTypes.add(draft.type());
            openedNames.add(draft.name());
            return ids.incrementAndGet();
        }

        @Override
        public void closeSpan(ObsTraceScope scope, Long spanId, SpanClose close) {
            closes.add(close);
        }

        @Override
        public void finish(ObsTraceScope scope, ObsStatus status, String errorSummary) {
            this.finishedStatus = status;
            this.finishedError = errorSummary;
        }

        @Override
        public void cancelSession(long sessionId, String reason) {
            // 本测试不涉及
        }
    }

    /**
     * 所有方法抛异常的实现：验证埋点降级。
     */
    private static final class ThrowingRecorder implements TraceRecorder {

        @Override
        public Optional<ObsTraceScope> beginConsole(TraceStart start) {
            throw new IllegalStateException("mock");
        }

        @Override
        public Optional<ObsTraceScope> beginWorkflow(TraceStart start, String workflowRunId, String attrsJson) {
            throw new IllegalStateException("mock");
        }

        @Override
        public Optional<ObsTraceScope> resumeWorkflow(String traceId) {
            throw new IllegalStateException("mock");
        }

        @Override
        public Long openSpan(ObsTraceScope scope, SpanDraft draft) {
            throw new IllegalStateException("mock");
        }

        @Override
        public void closeSpan(ObsTraceScope scope, Long spanId, SpanClose close) {
            throw new IllegalStateException("mock");
        }

        @Override
        public void finish(ObsTraceScope scope, ObsStatus status, String errorSummary) {
            throw new IllegalStateException("mock");
        }

        @Override
        public void cancelSession(long sessionId, String reason) {
            throw new IllegalStateException("mock");
        }
    }
}

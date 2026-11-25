package com.agenthub.obs.application;

import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.common.api.PageResult;
import com.agenthub.obs.api.dto.ObsTracePageReq;
import com.agenthub.obs.api.dto.ObsTraceResp;
import com.agenthub.obs.domain.model.ObsChannel;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.ObsTrace;
import com.agenthub.obs.domain.model.ObsTraceQuery;
import com.agenthub.obs.domain.repository.ObsSpanRepository;
import com.agenthub.obs.domain.repository.ObsTraceRepository;
import com.agenthub.obs.domain.spi.WorkflowRunLookup;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按工作流过滤 trace 的用例单测。所属域：obs 的 application。
 *
 * <p>只验查询编排：defId 是否被换成 run 集合、可见范围是否带上、DTO 是否带回工作流名。
 * 仓储与权限均用替身，不连库。
 */
class TraceQueryAppServiceWorkflowTest {

    private ObsTraceRepository traces;
    private ObsAccess access;
    private WorkflowRunLookup lookup;
    private TraceQueryAppService service;

    @BeforeEach
    void setUp() {
        traces = mock(ObsTraceRepository.class);
        access = mock(ObsAccess.class);
        lookup = mock(WorkflowRunLookup.class);
        service = new TraceQueryAppService(
                mock(RunSessionRepository.class), traces, mock(ObsSpanRepository.class),
                mock(AgentDefRepository.class), access, lookup);
    }

    @Test
    @DisplayName("workflowId 过滤：defId→run 集合，DTO 带回工作流名与 runId")
    void filtersByWorkflowAndReturnsName() {
        when(access.ownerFilter()).thenReturn(9L);
        when(lookup.recentRunIds(5L, 500)).thenReturn(List.of("12", "13"));
        ObsTrace row = trace("t-1", "12");
        when(traces.page(any())).thenAnswer(invocation -> {
            ObsTraceQuery query = invocation.getArgument(0);
            assertThat(query.getWorkflowRunIds()).containsExactly("12", "13");
            assertThat(query.getUserId()).isEqualTo(9L);
            assertThat(query.getSessionId()).isNull();
            return PageResult.of(1, 1, 20, List.of(row));
        });

        ObsTracePageReq req = new ObsTracePageReq();
        req.setWorkflowId(5L);
        PageResult<ObsTraceResp> page = service.tracesByWorkflow(req);

        assertThat(page.getTotal()).isEqualTo(1);
        ObsTraceResp resp = page.getRecords().get(0);
        assertThat(resp.getWorkflowRunId()).isEqualTo("12");
        assertThat(resp.getWorkflowId()).isEqualTo(5L);
        assertThat(resp.getWorkflowName()).isEqualTo("请假审批");
        assertThat(resp.getChannel()).isEqualTo(ObsChannel.WORKFLOW.name());
        assertThat(resp.getStatus()).isEqualTo(ObsStatus.OK.name());
    }

    @Test
    @DisplayName("该工作流还没有 run：直接空页，不再查 trace 表")
    void emptyWhenNoRuns() {
        when(access.ownerFilter()).thenReturn(null);
        when(lookup.recentRunIds(5L, 500)).thenReturn(List.of());

        ObsTracePageReq req = new ObsTracePageReq();
        req.setWorkflowId(5L);
        PageResult<ObsTraceResp> page = service.tracesByWorkflow(req);

        assertThat(page.getTotal()).isZero();
        assertThat(page.getRecords()).isEmpty();
        verify(traces, never()).page(any());
    }

    @Test
    @DisplayName("缺 workflowId：按 WORKFLOW 通道分页查全部，不反查 run 集合")
    void allWorkflowTracesWhenNoDefId() {
        when(access.ownerFilter()).thenReturn(9L);
        when(traces.page(any())).thenAnswer(invocation -> {
            ObsTraceQuery query = invocation.getArgument(0);
            assertThat(query.getWorkflowRunIds()).isNull();
            assertThat(query.getChannel()).isEqualTo(ObsChannel.WORKFLOW);
            assertThat(query.getUserId()).isEqualTo(9L);
            return PageResult.of(0, 1, 20, List.of());
        });

        PageResult<ObsTraceResp> page = service.tracesByWorkflow(new ObsTracePageReq());

        assertThat(page.getTotal()).isZero();
        verify(traces).page(any());
        verify(lookup, never()).recentRunIds(anyLong(), anyInt());
    }

    private ObsTrace trace(String traceId, String workflowRunId) {
        ObsTrace trace = new ObsTrace();
        trace.setTraceId(traceId);
        trace.setSessionId(0L);
        trace.setUserId(9L);
        trace.setChannel(ObsChannel.WORKFLOW);
        trace.setStatus(ObsStatus.OK);
        trace.setWorkflowRunId(workflowRunId);
        trace.setTitle("工作流 · 请假审批 · v2");
        trace.setAttrsJson("{\"workflow_def_id\":5,\"workflow_name\":\"请假审批\"}");
        trace.setStartTime(LocalDateTime.now());
        return trace;
    }
}

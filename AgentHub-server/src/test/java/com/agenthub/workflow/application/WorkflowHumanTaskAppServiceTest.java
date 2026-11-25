package com.agenthub.workflow.application;

import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.agenthub.workflow.support.InMemoryWorkflowStore;
import com.agenthub.workflow.support.RecordingTraceWriter;
import com.agenthub.workflow.support.RecordingWorkbenchTimeline;
import com.agenthub.workflow.support.WorkflowTestGraphs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人工待办用例单测。所属域：workflow 的 application。
 * <p>验证 approve 后续跑至 SUCCESS、reject 收 FAILED、超时 AUTO_APPROVE 自动续跑；
 * 用内存仓储与假 AGENT 端口，不出站、不连库。
 */
class WorkflowHumanTaskAppServiceTest {

    private InMemoryWorkflowStore store;
    private WorkflowHumanTaskAppService service;
    private WorkflowRun run;
    /** 刀5B：工作台会话的时间线追写替身（断言人工恢复后结果回写同一会话） */
    private RecordingWorkbenchTimeline timeline;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        timeline = new RecordingWorkbenchTimeline();
        ObjectMapper json = new ObjectMapper();
        WorkflowGraphCodec codec = new WorkflowGraphCodec(json);
        WorkflowProperties properties = new WorkflowProperties();
        properties.setTrialWallMs(5_000L);
        WorkflowEngine engine = new WorkflowEngine(store.runs(), store.nodeRuns(), store.humanTasks(),
                (agentId, message, userId, runId) -> "已处理",
                (toolId, args) -> "ok",
                codec, properties, json, new RecordingTraceWriter());
        WorkflowRunAssembler assembler = new WorkflowRunAssembler(store.nodeRuns(), store.humanTasks());
        OperatorContext operator = new OperatorContext() {
            @Override
            public Long userIdOrNull() {
                return 7L;
            }

            @Override
            public long requireUserId() {
                return 7L;
            }

            @Override
            public String usernameOrNull() {
                return "tester";
            }

            @Override
            public String clientIp() {
                return "127.0.0.1";
            }

            @Override
            public String userAgent() {
                return "test";
            }

            @Override
            public String requestMethod() {
                return "POST";
            }

            @Override
            public String requestUri() {
                return "/test";
            }
        };
        service = new WorkflowHumanTaskAppService(store.humanTasks(), store.nodeRuns(), store.runs(), engine, codec, assembler,
                () -> 1L, operator, (module, action, targetType, targetId, summary) -> {
                }, store.defs(), json, new RecordingTraceWriter(), timeline);
        run = seedPausedRun(engine);
    }

    @Test
    @DisplayName("approve：待办 DONE，节点 SUCCESS，run 续跑到 SUCCESS")
    void approveResumesRun() {
        WorkflowHumanTask task = openTask();

        service.approve(task.getId(), "同意");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(store.findTask(task.getId()).orElseThrow().getStatus()).isEqualTo("DONE");
        assertThat(nodeStatus("review")).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("reject：节点 FAILED，run 收 FAILED")
    void rejectFailsRun() {
        WorkflowHumanTask task = openTask();

        service.reject(task.getId(), "资料不全");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.FAILED.name());
        assertThat(store.run(run.getId()).getErrorSummary()).contains("人工驳回");
        assertThat(nodeStatus("review")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("超时 AUTO_APPROVE：到期待办自动通过并续跑")
    void timeoutAutoApprove() {
        WorkflowHumanTask task = openTask();
        // 内存仓储返回同一对象引用：直接把到期时间拨到过去，模拟懒检查命中
        task.setExpireTime(LocalDateTime.now().minusSeconds(5));
        task.setTimeoutPolicy("AUTO_APPROVE");

        service.resolveExpired(store.run(run.getId()));

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(store.findTask(task.getId()).orElseThrow().getStatus()).isEqualTo("EXPIRED");
    }

    /**
     * 刀5B：工作台触发的 run（workbench_session_id 非空）在人工处理完并到终态后，
     * 结果必须追写回<b>同一条会话</b>，用户不必再发一句话才看到。
     */
    @Test
    @DisplayName("工作台 run 人工通过后：结果追写回同一会话")
    void workbenchRunProjectsResultBackToSession() {
        run.setWorkbenchSessionId(66L);
        WorkflowHumanTask task = openTask();

        service.approve(task.getId(), "同意");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(timeline.lines()).isNotEmpty();
        // 全部追写都落在同一条工作台会话上，不会串到别的会话
        assertThat(timeline.lines()).allMatch(line -> line.sessionId() == 66L);
        // 先一条「人工待办已通过 + 运行已结束」的系统说明，再一条助手正文
        assertThat(timeline.systemTexts()).anyMatch(text -> text.contains("人工待办已通过"));
        assertThat(timeline.assistantTexts()).hasSize(1);
    }

    /** 刀5B：非工作台 run（试跑 / 应用触发）不往任何工作台会话追写。 */
    @Test
    @DisplayName("试跑 run 人工通过后：不追写工作台时间线")
    void trialRunDoesNotProjectToWorkbench() {
        WorkflowHumanTask task = openTask();

        service.approve(task.getId(), "同意");

        assertThat(timeline.lines()).isEmpty();
    }

    private WorkflowHumanTask openTask() {
        List<WorkflowHumanTask> tasks = store.listOpenByRunId(run.getId());
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    private String nodeStatus(String code) {
        for (WorkflowNodeRun row : store.listByRunId(run.getId())) {
            if (code.equals(row.getNodeCode())) {
                return row.getStatus();
            }
        }
        return null;
    }

    private WorkflowRun seedPausedRun(WorkflowEngine engine) {
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "review", "HUMAN",
                        "{\"timeoutPolicy\":\"AUTO_REJECT\",\"timeoutMs\":60000}") + ","
                        + WorkflowTestGraphs.node("n3", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null));
        WorkflowRun created = new WorkflowRun();
        created.setTenantId(1L);
        created.setWorkflowId(10L);
        created.setReleaseId(20L);
        created.setReleaseVersion(1);
        created.setStatus(WorkflowRunStatus.CREATED.name());
        created.setTriggerType("TRIAL");
        created.setTriggeredBy(7L);
        WorkflowTestGraphs.run(created, graph, "{}");
        Long id = store.insertRun(created);
        engine.execute(created, 7L);
        return store.run(id);
    }
}


package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.api.dto.WorkflowHumanTaskItemResp;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowHumanTask;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 人工待办处理人规则 + 待办列表窄测。所属域：workflow 的 application。
 *
 * <p>覆盖 W4 验收：USER 命中/未命中、ROLE 未命中（无角色上下文）、空规则兼容、
 * mine=1 只返回可处理 OPEN 待办。不出站、不连库、不依赖 Sa-Token 上下文。
 */
class WorkflowHumanTaskAssigneeTest {

    private InMemoryWorkflowStore store;
    private WorkflowGraphCodec codec;
    private WorkflowEngine engine;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        json = new ObjectMapper();
        codec = new WorkflowGraphCodec(json);
        engine = new WorkflowEngine(store.runs(), store.nodeRuns(), store.humanTasks(),
                (agentId, message, userId, runId) -> "已处理",
                (toolId, args) -> "ok",
                codec, new WorkflowProperties(), json, new RecordingTraceWriter());
    }

    @Test
    @DisplayName("USER 未命中：403 中文拒绝，任务仍 OPEN，run 仍 PAUSED_HUMAN")
    void userRuleRejectsUnlistedOperator() {
        WorkflowRun run = pausedRun("USER:9");
        WorkflowHumanTaskAppService service = service(7L);
        WorkflowHumanTask task = openTask(run);

        assertThatThrownBy(() -> service.approve(task.getId(), "同意"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("你不是该节点处理人");

        assertThat(store.findTask(task.getId()).orElseThrow().getStatus()).isEqualTo("OPEN");
        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.PAUSED_HUMAN.name());
    }

    @Test
    @DisplayName("USER 命中：名单内用户可通过并续跑")
    void userRuleAllowsListedOperator() {
        WorkflowRun run = pausedRun("USER:7,9");
        WorkflowHumanTaskAppService service = service(7L);
        WorkflowHumanTask task = openTask(run);

        service.approve(task.getId(), "同意");

        assertThat(store.findTask(task.getId()).orElseThrow().getStatus()).isEqualTo("DONE");
        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
    }

    @Test
    @DisplayName("ROLE 未命中（当前用户无角色上下文）：403 且提示所需角色")
    void roleRuleRejectsWithoutRole() {
        WorkflowRun run = pausedRun("ROLE:ops_admin");
        WorkflowHumanTaskAppService service = service(7L);
        WorkflowHumanTask task = openTask(run);

        assertThatThrownBy(() -> service.reject(task.getId(), "不同意"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("你不是该节点处理人")
                .hasMessageContaining("ops_admin");
    }

    @Test
    @DisplayName("空规则兼容刀2：任何有 human:act 的用户可通过")
    void emptyRuleStaysCompatible() {
        // 图里没写 assigneeRule，任务行 assignee_rule 为 null
        WorkflowRun run = pausedRun(null);
        WorkflowHumanTaskAppService service = service(7L);
        WorkflowHumanTask task = openTask(run);

        service.approve(task.getId(), "同意");

        assertThat(store.run(run.getId()).getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
    }

    @Test
    @DisplayName("mine=1：只返回当前用户可处理的 OPEN，并带工作流名与规则中文")
    void mineListFiltersActionableTasks() {
        WorkflowDef def = new WorkflowDef();
        def.setId(10L);
        def.setTenantId(1L);
        def.setCode("order_approval");
        def.setName("订单审批流");
        store.putDef(def);

        WorkflowRun mineRun = pausedRun("USER:7");
        pausedRun("USER:9");

        List<WorkflowHumanTaskItemResp> items = service(7L).list(true, null);

        assertThat(items).hasSize(1);
        WorkflowHumanTaskItemResp item = items.get(0);
        assertThat(item.getRunId()).isEqualTo(mineRun.getId());
        assertThat(item.getWorkflowName()).isEqualTo("订单审批流");
        assertThat(item.getAssigneeText()).contains("指定用户");
        assertThat(item.isActionable()).isTrue();

        // 换个用户：不属于自己，列表应为空
        assertThat(service(8L).list(true, null)).isEmpty();
    }

    @Test
    @DisplayName("mine=0：按状态过滤，OPEN 仍可见（不做处理人过滤）")
    void listWithoutMineShowsAllOpen() {
        pausedRun("USER:9");

        List<WorkflowHumanTaskItemResp> items = service(7L).list(false, "OPEN");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).isActionable()).isFalse();
    }

    private WorkflowHumanTaskAppService service(long operatorId) {
        OperatorContext operator = new OperatorContext() {
            @Override
            public Long userIdOrNull() {
                return operatorId;
            }

            @Override
            public long requireUserId() {
                return operatorId;
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
        WorkflowRunAssembler assembler = new WorkflowRunAssembler(store.nodeRuns(), store.humanTasks());
        return new WorkflowHumanTaskAppService(store.humanTasks(), store.nodeRuns(), store.runs(), engine,
                codec, assembler, () -> 1L, operator,
                (module, action, targetType, targetId, summary) -> {
                }, store.defs(), json, new RecordingTraceWriter(), new RecordingWorkbenchTimeline());
    }

    private WorkflowHumanTask openTask(WorkflowRun run) {
        List<WorkflowHumanTask> tasks = store.listOpenByRunId(run.getId());
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    private WorkflowRun pausedRun(String assigneeRule) {
        String humanConfig = "{\"timeoutPolicy\":\"AUTO_REJECT\",\"timeoutMs\":60000"
                + (assigneeRule == null ? "" : ",\"assigneeRule\":\"" + assigneeRule + "\"")
                + "}";
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "review", "HUMAN", humanConfig) + ","
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

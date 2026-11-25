package com.agenthub.workflow.application;

import com.agenthub.admin.domain.model.AppIdentity;
import com.agenthub.workflow.api.dto.WorkflowAppInvokeReq;
import com.agenthub.workflow.api.dto.WorkflowAppInvokeResp;
import com.agenthub.workflow.domain.enums.WorkflowDefStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowTriggerType;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.agenthub.workflow.support.InMemoryWorkflowStore;
import com.agenthub.workflow.support.RecordingTraceWriter;
import com.agenthub.workflow.support.WorkflowGrantTestSupport;
import com.agenthub.workflow.support.WorkflowTestGraphs;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.common.error.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 应用触发工作流单测。所属域：workflow 的 application。
 * <p>覆盖：有授权建 run 并跑到 SUCCESS、trigger=APP、无授权拒、未发布拒、幂等键复用、
 * 输入非对象拒；引擎用同一套 WorkflowEngine，AGENT 端口用假实现不出站。
 */
class WorkflowAppInvokeAppServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long WORKFLOW_ID = 10L;
    private static final long APP_ID = 100L;

    private InMemoryWorkflowStore store;
    private WorkflowGrantTestSupport grantStore;
    private WorkflowAppInvokeAppService service;
    private WorkflowRelease published;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowStore();
        grantStore = new WorkflowGrantTestSupport();
        ObjectMapper json = new ObjectMapper();
        WorkflowGraphCodec codec = new WorkflowGraphCodec(json);
        WorkflowProperties properties = new WorkflowProperties();
        properties.setTrialWallMs(5_000L);
        WorkflowEngine engine = new WorkflowEngine(store.runs(), store.nodeRuns(), store.humanTasks(),
                (agentId, message, userId, runId) -> "ok",
                (toolId, args) -> "ok",
                codec, properties, json, new RecordingTraceWriter());

        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null));
        published = grantStore.putRelease(WORKFLOW_ID, 1, graph);

        WorkflowAppGrantAppService grantService = new WorkflowAppGrantAppService(
                grantStore.grants(), store.defs(), grantStore.releases(),
                () -> TENANT_ID, operator(), (m, a, t, id, s) -> {
        });

        // 同步执行器：单测里把异步调度收敛到当前线程，断言才稳定
        Executor direct = Runnable::run;
        service = new WorkflowAppInvokeAppService(grantService, store.defs(), grantStore.releases(),
                store.runs(), store.nodeRuns(), () -> TENANT_ID, codec, engine, properties,
                (m, a, t, id, s) -> {
                }, json, direct);
    }

    @Test
    @DisplayName("有授权：建 APP run 并跑到 SUCCESS")
    void invokeCreatesAppRun() {
        seedPublishedDef();
        grantStore.putGrant(APP_ID, WORKFLOW_ID, null);

        WorkflowAppInvokeResp resp = service.invoke(app(), req(null));

        assertThat(resp.getRunId()).isNotNull();
        assertThat(resp.getTriggerType()).isEqualTo(WorkflowTriggerType.APP.name());
        WorkflowRun run = store.run(resp.getRunId());
        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS.name());
        assertThat(run.getTriggerType()).isEqualTo(WorkflowTriggerType.APP.name());
        assertThat(run.getAppId()).isEqualTo(APP_ID);
        assertThat(run.getTriggeredBy()).isNull();
        assertThat(run.getReleaseId()).isEqualTo(published.getId());
    }

    @Test
    @DisplayName("无授权：403，不建 run")
    void invokeWithoutGrantRejected() {
        seedPublishedDef();

        assertThatThrownBy(() -> service.invoke(app(), req(null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未获此工作流的触发授权");
    }

    @Test
    @DisplayName("未发布：400，不建 run（草稿不可被应用触发）")
    void invokeDraftRejected() {
        WorkflowDef draft = new WorkflowDef();
        draft.setId(WORKFLOW_ID);
        draft.setTenantId(TENANT_ID);
        draft.setCode("draft_only");
        draft.setName("未发布工作流");
        draft.setStatus(WorkflowDefStatus.initial());
        store.putDef(draft);
        grantStore.putGrant(APP_ID, WORKFLOW_ID, null);

        assertThatThrownBy(() -> service.invoke(app(), req(null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未发布");
    }

    @Test
    @DisplayName("幂等：同 app 同 clientRequestId 返回同一 run")
    void invokeIsIdempotent() {
        seedPublishedDef();
        grantStore.putGrant(APP_ID, WORKFLOW_ID, null);

        WorkflowAppInvokeResp first = service.invoke(app(), req("req-001"));
        WorkflowAppInvokeResp second = service.invoke(app(), req("req-001"));

        assertThat(second.getRunId()).isEqualTo(first.getRunId());
        assertThat(store.listRuns(WORKFLOW_ID, 10)).hasSize(1);
    }

    @Test
    @DisplayName("input 非对象：400")
    void invokeRejectsNonObjectInput() {
        seedPublishedDef();
        grantStore.putGrant(APP_ID, WORKFLOW_ID, null);
        WorkflowAppInvokeReq req = req(null);
        req.setInput(new ObjectMapper().createArrayNode());

        assertThatThrownBy(() -> service.invoke(app(), req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("input 必须是 JSON 对象");
    }

    @Test
    @DisplayName("钉死 release：grant.release_id 优先于默认发布指针")
    void invokeUsesPinnedRelease() {
        seedPublishedDef();
        // 再发一版并推进默认指针，grant 仍钉死第 1 版
        WorkflowRelease second = grantStore.putRelease(WORKFLOW_ID, 2, published.getGraphJson());
        store.findDef(WORKFLOW_ID).orElseThrow().setDefaultReleaseId(second.getId());
        grantStore.putGrant(APP_ID, WORKFLOW_ID, published.getId());

        WorkflowAppInvokeResp resp = service.invoke(app(), req(null));

        assertThat(resp.getReleaseId()).isEqualTo(published.getId());
    }

    private void seedPublishedDef() {
        WorkflowDef def = new WorkflowDef();
        def.setId(WORKFLOW_ID);
        def.setTenantId(TENANT_ID);
        def.setCode("order_flow");
        def.setName("订单流程");
        def.setStatus(WorkflowDefStatus.published());
        def.setDefaultReleaseId(published.getId());
        store.putDef(def);
    }

    private WorkflowAppInvokeReq req(String clientRequestId) {
        WorkflowAppInvokeReq req = new WorkflowAppInvokeReq();
        req.setWorkflowId(WORKFLOW_ID);
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("query", "hello");
        req.setInput(input);
        req.setClientRequestId(clientRequestId);
        return req;
    }

    private AppIdentity app() {
        return new AppIdentity(APP_ID, "app_test01", "订单系统", 5L);
    }

    private OperatorContext operator() {
        return new OperatorContext() {
            @Override
            public Long userIdOrNull() {
                return 1L;
            }

            @Override
            public long requireUserId() {
                return 1L;
            }

            @Override
            public String usernameOrNull() {
                return "admin";
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
    }
}

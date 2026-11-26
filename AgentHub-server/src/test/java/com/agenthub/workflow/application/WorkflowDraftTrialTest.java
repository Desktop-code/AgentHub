package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.api.dto.WorkflowRunTrialReq;
import com.agenthub.workflow.domain.WorkflowConflictException;
import com.agenthub.workflow.domain.model.WorkflowBindingSnapshot;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowDraft;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowValidationResult;
import com.agenthub.workflow.domain.repository.WorkflowDraftRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.service.WorkflowGraphValidator;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.agenthub.workflow.support.InMemoryWorkflowStore;
import com.agenthub.workflow.support.RecordingTraceWriter;
import com.agenthub.workflow.support.RecordingWorkbenchTimeline;
import com.agenthub.workflow.support.WorkflowTestGraphs;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按当前草稿调试，以及切换默认发布指针。所属域：workflow 的 application 测试。
 * <p>调试不写 release、不改指针；切换只改草稿和指针，不新增版本，旧 run 的图保持原样。
 */
class WorkflowDraftTrialTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void draftTrialCopiesTheDraftAndLeavesTheReleasePointerAlone() {
        String releaseGraph = startEnd("start", "end");
        String draftGraph = startEnd("start", "end_draft");
        Fixture fixture = fixture(releaseGraph, draftGraph, 20L);
        WorkflowRun oldRun = seedRun(fixture, releaseGraph, 20L, 6);

        WorkflowRunTrialReq req = new WorkflowRunTrialReq();
        req.setUseDraft(true);
        req.setWorkflowId(fixture.workflowId);
        req.setInput(query("现在的画布"));
        WorkflowRun created = fixture.runs.trial(req).getRun();

        assertNull(created.getReleaseId());
        assertNull(created.getReleaseVersion());
        // 当时默认发布是 v6：快照记在这条 run 上，但 release 列仍留空（未发布）
        assertEquals(6, created.getDraftBaseReleaseVersion());
        assertEquals(draftGraph, created.getGraphJson());
        assertEquals("TRIAL", created.getTriggerType());
        assertNull(created.getWorkbenchSessionId());
        assertEquals(0, fixture.releases.inserts);
        assertEquals(20L, fixture.store.findDef(fixture.workflowId).orElseThrow().getDefaultReleaseId());
        WorkflowRun untouched = fixture.store.run(oldRun.getId());
        assertEquals(20L, untouched.getReleaseId());
        assertEquals(6, untouched.getReleaseVersion());
        assertEquals(releaseGraph, untouched.getGraphJson());
    }

    @Test
    void draftTrialRejectsAnInvalidGraphWithoutInsertingARun() {
        Fixture fixture = fixture(startEnd("start", "end"), "{\"nodes\":[],\"edges\":[]}", 20L);
        WorkflowRunTrialReq req = new WorkflowRunTrialReq();
        req.setUseDraft(true);
        req.setWorkflowId(fixture.workflowId);
        req.setInput(query("不会开跑"));
        BizException ex = assertThrows(BizException.class, () -> fixture.runs.trial(req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("调试校验未通过"));
        assertNull(fixture.store.run(1L));
        assertEquals(0, fixture.releases.inserts);
        assertEquals(20L, fixture.store.findDef(fixture.workflowId).orElseThrow().getDefaultReleaseId());
    }

    @Test
    void draftTrialKeepsTheDefaultVersionItSawEvenAfterAnotherPublish() {
        String v6Graph = startEnd("start", "v6");
        String draft = startEnd("start", "draft");
        Fixture fixture = fixture(v6Graph, draft, 20L);
        WorkflowRunTrialReq req = new WorkflowRunTrialReq();
        req.setUseDraft(true);
        req.setWorkflowId(fixture.workflowId);
        req.setInput(query("当时的默认版本"));
        WorkflowRun created = fixture.runs.trial(req).getRun();
        assertEquals(6, created.getDraftBaseReleaseVersion());

        // 之后又发布 v7 并把默认指针改过去：旧记录仍显示当时看到的 v6，release 列也仍是空
        fixture.releases.put(release(23L, 7, draft));
        fixture.store.findDef(fixture.workflowId).orElseThrow().setDefaultReleaseId(23L);
        WorkflowRun reread = fixture.store.run(created.getId());
        assertEquals(6, reread.getDraftBaseReleaseVersion());
        assertNull(reread.getReleaseVersion());

        // 列表一行带出当时的版本、未发布与用户问题
        var summary = fixture.runs.toSummary(reread);
        assertEquals(6, summary.getDraftBaseReleaseVersion());
        assertNull(summary.getReleaseVersion());
        assertEquals("当时的默认版本", summary.getQuery());
    }

    @Test
    void draftTrialOnNeverPublishedDefinitionLeavesTheBaseVersionEmpty() {
        Fixture fixture = fixture(startEnd("start", "end"), startEnd("start", "draft"), 20L);
        fixture.store.findDef(fixture.workflowId).orElseThrow().setDefaultReleaseId(null);
        WorkflowRunTrialReq req = new WorkflowRunTrialReq();
        req.setUseDraft(true);
        req.setWorkflowId(fixture.workflowId);
        req.setInput(query("还没发布过"));
        WorkflowRun created = fixture.runs.trial(req).getRun();

        // 从未发布：没有版本号可写，列表只显示「未发布」
        assertNull(created.getDraftBaseReleaseVersion());
        assertNull(created.getReleaseVersion());
        assertEquals("还没发布过", fixture.runs.toSummary(created).getQuery());
    }

    @Test
    void workbenchUsesThePointerAndDoesNotRewriteOlderRuns() {
        String v1 = startEnd("start", "v1");
        String v2 = startEnd("start", "v2");
        Fixture fixture = fixture(v2, v2, 22L);
        fixture.releases.put(release(21L, 1, v1));
        WorkflowRun older = seedRun(fixture, v2, 22L, 2);

        fixture.store.findDef(fixture.workflowId).orElseThrow().setDefaultReleaseId(21L);
        var outcome = fixture.runs.runFromWorkbench(fixture.workflowId, "用旧版", 9L, 7L);

        assertEquals(21L, outcome.run().getReleaseId());
        assertEquals(1, outcome.run().getReleaseVersion());
        assertEquals(v1, outcome.run().getGraphJson());
        assertEquals("WORKBENCH", outcome.run().getTriggerType());
        WorkflowRun still = fixture.store.run(older.getId());
        assertEquals(22L, still.getReleaseId());
        assertEquals(v2, still.getGraphJson());
    }

    @Test
    void activateRestoresTheOldReleaseWithoutCreatingAVersion() {
        String v1 = startEnd("start", "v1");
        String v2 = startEnd("start", "v2");
        ActivateFixture fixture = activateFixture(v2, v2);
        fixture.releases.put(release(21L, 1, v1));
        fixture.releases.put(release(22L, 2, v2));

        var resp = fixture.defs.activate(10L, 21L, null);

        assertEquals(21L, resp.getId());
        assertEquals(1, resp.getVersion());
        assertEquals(v1, fixture.drafts.get(10L).getGraphJson());
        assertEquals(21L, fixture.store.findDef(10L).orElseThrow().getDefaultReleaseId());
        assertEquals(0, fixture.releases.inserts);
        assertEquals(2, fixture.releases.maxVersion(10L));
        assertEquals(v1, fixture.releases.findById(21L).orElseThrow().getGraphJson());
        assertEquals(v2, fixture.releases.findById(22L).orElseThrow().getGraphJson());
    }

    @Test
    void activateRefusesToOverwriteUnpublishedChangesUntilConfirmed() {
        String v1 = startEnd("start", "v1");
        String v2 = startEnd("start", "v2");
        String dirty = startEnd("start", "dirty");
        ActivateFixture fixture = activateFixture(v2, dirty);
        fixture.releases.put(release(21L, 1, v1));
        fixture.releases.put(release(22L, 2, v2));

        assertThrows(WorkflowConflictException.class, () -> fixture.defs.activate(10L, 21L, null));
        assertEquals(dirty, fixture.drafts.get(10L).getGraphJson());
        assertEquals(22L, fixture.store.findDef(10L).orElseThrow().getDefaultReleaseId());

        var req = new com.agenthub.workflow.api.dto.WorkflowReleaseActivateReq();
        req.setOverwrite(true);
        var resp = fixture.defs.activate(10L, 21L, req);
        assertEquals(21L, resp.getId());
        assertEquals(v1, fixture.drafts.get(10L).getGraphJson());
        assertEquals(0, fixture.releases.inserts);
    }

    @Test
    void publishAfterASwitchStillAppendsANewVersion() {
        String v1 = startEnd("start", "v1");
        String edited = startEnd("start", "edited");
        ActivateFixture fixture = activateFixture(v1, edited);
        fixture.releases.put(release(21L, 1, v1));
        fixture.store.findDef(10L).orElseThrow().setDefaultReleaseId(21L);

        var published = fixture.defs.publish(10L, null);

        assertEquals(2, published.getVersion());
        assertEquals(1, fixture.releases.inserts);
        assertEquals(published.getId(), fixture.store.findDef(10L).orElseThrow().getDefaultReleaseId());
        assertEquals(v1, fixture.releases.findById(21L).orElseThrow().getGraphJson());
        assertEquals(edited, fixture.releases.findById(published.getId()).orElseThrow().getGraphJson());
    }

    private ObjectNode query(String text) {
        ObjectNode input = json.createObjectNode();
        input.put("query", text);
        return input;
    }

    private static String startEnd(String startCode, String endCode) {
        return WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", startCode, "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", endCode, "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null));
    }

    private WorkflowRun seedRun(Fixture fixture, String graph, long releaseId, int version) {
        WorkflowRun run = new WorkflowRun();
        run.setTenantId(1L);
        run.setWorkflowId(fixture.workflowId);
        run.setReleaseId(releaseId);
        run.setReleaseVersion(version);
        run.setStatus("SUCCESS");
        run.setTriggerType("TRIAL");
        run.setGraphJson(graph);
        run.setInputJson("{\"query\":\"旧的\"}");
        fixture.store.insertRun(run);
        return run;
    }

    private Fixture fixture(String releaseGraph, String draftGraph, long releaseId) {
        InMemoryWorkflowStore store = new InMemoryWorkflowStore();
        WorkflowDef def = new WorkflowDef();
        def.setId(10L);
        def.setTenantId(1L);
        def.setCode("debug_demo");
        def.setName("调试演示");
        def.setStatus("HAS_RELEASE");
        def.setDefaultReleaseId(releaseId);
        store.putDef(def);
        MemoryReleases releases = new MemoryReleases();
        releases.put(release(releaseId, 6, releaseGraph));
        MemoryDrafts drafts = new MemoryDrafts();
        WorkflowDraft draft = new WorkflowDraft();
        draft.setWorkflowId(10L);
        draft.setGraphJson(draftGraph);
        drafts.put(draft);
        WorkflowGraphCodec codec = new WorkflowGraphCodec(json);
        WorkflowProperties properties = new WorkflowProperties();
        WorkflowEngine engine = new WorkflowEngine(store.runs(), store.nodeRuns(), store.humanTasks(),
                (agentId, message, userId, runId) -> "不会调用", (toolId, args) -> "工具结果",
                codec, properties, json, new RecordingTraceWriter());
        WorkflowRunAssembler assembler = new WorkflowRunAssembler(store.nodeRuns(), store.humanTasks());
        OperatorContext operator = new OperatorStub();
        WorkflowHumanTaskAppService human = new WorkflowHumanTaskAppService(store.humanTasks(), store.nodeRuns(),
                store.runs(), engine, codec, assembler, () -> 1L, operator,
                (module, action, targetType, targetId, summary) -> {
                }, store.defs(), json, new RecordingTraceWriter(), new RecordingWorkbenchTimeline());
        WorkflowTrialDispatcher dispatcher = new WorkflowTrialDispatcher(engine, store.runs(), Runnable::run);
        WorkflowRunAppService runs = new WorkflowRunAppService(store.runs(), store.nodeRuns(), store.humanTasks(),
                releases, store.defs(), () -> 1L, operator, codec, engine, assembler, human,
                (module, action, targetType, targetId, summary) -> {
                }, properties, json, new RecordingTraceWriter(), dispatcher, drafts, new ValidatorGate());
        return new Fixture(store, runs, releases, 10L);
    }

    private ActivateFixture activateFixture(String currentGraph, String draftGraph) {
        InMemoryWorkflowStore store = new InMemoryWorkflowStore();
        WorkflowDef def = new WorkflowDef();
        def.setId(10L);
        def.setTenantId(1L);
        def.setCode("debug_demo");
        def.setName("调试演示");
        def.setStatus("HAS_RELEASE");
        def.setDefaultReleaseId(22L);
        store.putDef(def);
        MemoryReleases releases = new MemoryReleases();
        MemoryDrafts drafts = new MemoryDrafts();
        WorkflowDraft draft = new WorkflowDraft();
        draft.setWorkflowId(10L);
        draft.setGraphJson(draftGraph);
        drafts.put(draft);
        WorkflowGraphCodec codec = new WorkflowGraphCodec(json);
        WorkflowDefAppService defs = new WorkflowDefAppService(store.defs(), drafts, releases, () -> 1L,
                new OperatorStub(), codec, new ValidatorGate(),
                (module, action, targetType, targetId, summary) -> {
                }, new WorkflowProperties());
        if (!currentGraph.equals(draftGraph)) {
            // 占位：调用方再放入真正的 release；这里只保证草稿已在
        }
        return new ActivateFixture(store, defs, drafts, releases);
    }

    private static WorkflowRelease release(long id, int version, String graph) {
        WorkflowRelease release = new WorkflowRelease();
        release.setId(id);
        release.setWorkflowId(10L);
        release.setVersion(version);
        release.setGraphJson(graph);
        release.setFingerprint("fp-" + version);
        return release;
    }

    private record Fixture(InMemoryWorkflowStore store, WorkflowRunAppService runs,
                           MemoryReleases releases, Long workflowId) {
    }

    private record ActivateFixture(InMemoryWorkflowStore store, WorkflowDefAppService defs,
                                   MemoryDrafts drafts, MemoryReleases releases) {
    }

    /** 单测操作人：固定用户 7，不碰 Sa-Token。 */
    private static final class OperatorStub implements OperatorContext {
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
            return "/api/workflow/runs/trial";
        }
    }

    /**
     * 用空绑定走现成校验器，避免单测再接 Agent / 工具仓储。
     * 生产环境的 {@link WorkflowPublishCheck} 仍会解析真实绑定。
     */
    private static final class ValidatorGate extends WorkflowPublishCheck {
        private ValidatorGate() {
            super(null, new WorkflowProperties());
        }

        @Override
        public WorkflowValidationResult check(WorkflowGraph graph) {
            return WorkflowGraphValidator.validate(graph, WorkflowBindingSnapshot.empty(), true,
                    new WorkflowProperties().effectiveMaxNodesPerGraph(),
                    new WorkflowProperties().effectiveMaxLoopIterations());
        }

        @Override
        public void requirePublishable(WorkflowGraph graph) {
            WorkflowValidationResult result = check(graph);
            if (!result.passed()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "调试校验未通过");
            }
        }
    }

    private static final class MemoryDrafts implements WorkflowDraftRepository {
        private final Map<Long, WorkflowDraft> rows = new LinkedHashMap<>();

        void put(WorkflowDraft draft) {
            rows.put(draft.getWorkflowId(), draft);
        }

        WorkflowDraft get(Long workflowId) {
            return rows.get(workflowId);
        }

        @Override
        public Optional<WorkflowDraft> findByWorkflowId(Long workflowId) {
            return Optional.ofNullable(rows.get(workflowId));
        }

        @Override
        public void insert(WorkflowDraft draft, Long operatorId) {
            rows.put(draft.getWorkflowId(), draft);
        }

        @Override
        public int updateGraph(Long workflowId, String graphJson, LocalDateTime expectedUpdateTime, Long operatorId) {
            WorkflowDraft draft = rows.get(workflowId);
            if (draft == null) {
                return 0;
            }
            draft.setGraphJson(graphJson);
            draft.setUpdateTime(LocalDateTime.of(2026, 11, 26, 12, 0));
            return 1;
        }
    }

    private static final class MemoryReleases implements WorkflowReleaseRepository {
        private final Map<Long, WorkflowRelease> byId = new LinkedHashMap<>();
        private int inserts;

        void put(WorkflowRelease release) {
            byId.put(release.getId(), release);
        }

        @Override
        public int maxVersion(Long workflowId) {
            return byId.values().stream()
                    .filter(item -> workflowId.equals(item.getWorkflowId()))
                    .map(WorkflowRelease::getVersion)
                    .max(Integer::compareTo)
                    .orElse(0);
        }

        @Override
        public Long insert(WorkflowRelease release) {
            inserts++;
            long id = release.getId() == null ? 100L + inserts : release.getId();
            release.setId(id);
            byId.put(id, release);
            return id;
        }

        @Override
        public Optional<WorkflowRelease> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<WorkflowRelease> listByWorkflowId(Long workflowId) {
            List<WorkflowRelease> list = new ArrayList<>();
            for (WorkflowRelease release : byId.values()) {
                if (workflowId.equals(release.getWorkflowId())) {
                    list.add(release);
                }
            }
            return list;
        }
    }
}

package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.api.dto.WorkflowRunTrialReq;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.service.WorkflowAgentInvoker;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.agenthub.workflow.support.InMemoryWorkflowStore;
import com.agenthub.workflow.support.RecordingTraceWriter;
import com.agenthub.workflow.support.RecordingWorkbenchTimeline;
import com.agenthub.workflow.support.WorkflowTestGraphs;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试跑先返回 runId、后台继续，以及按 run 回复不绑会话。
 * 所属域：workflow 的 application 测试。
 */
class WorkflowTrialDispatchTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void trialReturnsBeforeTheAgentFinishesAndKeepsTrialTrigger() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wf-trial-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Harness harness = harness((agentId, message, userId, runId) -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "测试没有放行 Agent");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return "中断";
                }
                return "完成";
            }, pool, false);
            WorkflowRunDetail detail = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> harness.runs.trial(request(harness.releaseId)));
            assertNotNull(detail.getRun().getId());
            assertEquals("TRIAL", detail.getRun().getTriggerType());
            assertNull(detail.getRun().getWorkbenchSessionId());
            assertTrue(entered.await(3, TimeUnit.SECONDS), "后台没有开始调度");
            WorkflowRun mid = harness.store.run(detail.getRun().getId());
            WorkflowRunStatus midStatus = WorkflowRunStatus.parse(mid.getStatus());
            assertNotNull(midStatus);
            assertFalse(midStatus.terminal(), mid.getStatus());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            WorkflowRun done = mid;
            while (System.nanoTime() < deadline) {
                done = harness.store.run(detail.getRun().getId());
                WorkflowRunStatus status = WorkflowRunStatus.parse(done.getStatus());
                if (status != null && status.terminal()) {
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals("SUCCESS", done.getStatus(), done.getErrorSummary());
            assertNull(done.getWorkbenchSessionId());
            assertEquals("TRIAL", done.getTriggerType());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void fullQueueCancelsTheRunInsteadOfBlockingTheCaller() {
        Harness harness = harness((agentId, message, userId, runId) -> "不会执行",
                command -> {
                    throw new RejectedExecutionException("full");
                }, false);
        WorkflowRunDetail detail = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> harness.runs.trial(request(harness.releaseId)));
        assertEquals("CANCELLED", detail.getRun().getStatus());
        assertTrue(detail.getRun().getErrorSummary().contains("调试队列已满"));
        assertEquals("TRIAL", detail.getRun().getTriggerType());
        assertNull(detail.getRun().getWorkbenchSessionId());
    }

    @Test
    void replyContinuesTheSameTrialRunAndRejectsWorkbenchRuns() {
        // 同步执行器：trial 在请求线程跑到等待回复才返回，续跑仍是同一条 TRIAL
        Harness harness = harness(new WorkflowAgentInvoker() {
            private int calls;

            @Override
            public String invoke(Long agentId, String message, long userId, long runId) {
                calls++;
                return calls == 1 ? "你想用哪种语言？" : "已按你的选择继续";
            }

            @Override
            public Reply invokeForReply(Long agentId, String message, long userId, long runId) {
                String text = invoke(agentId, message, userId, runId);
                if (calls == 1) {
                    return new Reply(text, "{\"question\":\"你想用哪种语言？\","
                            + "\"options\":[{\"id\":\"python\",\"label\":\"Python\"},"
                            + "{\"id\":\"java\",\"label\":\"Java\"}]}");
                }
                return new Reply(text, "");
            }
        }, Runnable::run, true);

        WorkflowRunDetail waiting = harness.runs.trial(request(harness.releaseId));
        assertEquals("WAITING_REPLY", waiting.getRun().getStatus());
        assertNull(waiting.getRun().getWorkbenchSessionId());
        Long runId = waiting.getRun().getId();

        WorkflowRunDetail continued = harness.replies.reply(runId, "Python");
        assertEquals(runId, continued.getRun().getId());
        assertEquals("SUCCESS", continued.getRun().getStatus());
        assertEquals("TRIAL", continued.getRun().getTriggerType());
        assertNull(continued.getRun().getWorkbenchSessionId());

        WorkflowRun foreign = new WorkflowRun();
        foreign.setTenantId(1L);
        foreign.setWorkflowId(harness.workflowId);
        foreign.setStatus("WAITING_REPLY");
        foreign.setTriggerType("WORKBENCH");
        foreign.setWorkbenchSessionId(88L);
        foreign.setTriggeredBy(7L);
        foreign.setGraphJson(harness.graph);
        Long foreignId = harness.store.insertRun(foreign);
        BizException rejected = assertThrows(BizException.class,
                () -> harness.replies.reply(foreignId, "Python"));
        assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
        assertEquals("WORKBENCH", harness.store.run(foreignId).getTriggerType());
        assertEquals(88L, harness.store.run(foreignId).getWorkbenchSessionId());
    }

    @Test
    void replyReturnsBeforeTheBackgroundContinuesTheSameRun() throws Exception {
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wf-trial-reply-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Harness harness = harness(new WorkflowAgentInvoker() {
                @Override
                public String invoke(Long agentId, String message, long userId, long runId) {
                    if (calls.incrementAndGet() == 1) {
                        return "你想用哪种语言？";
                    }
                    // 第二段故意堵住：续跑请求若在请求线程同步跑引擎，这里会超时
                    secondEntered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return "中断";
                    }
                    return "已按你的选择继续";
                }

                @Override
                public Reply invokeForReply(Long agentId, String message, long userId, long runId) {
                    String text = invoke(agentId, message, userId, runId);
                    if (calls.get() == 1) {
                        return new Reply(text, "{\"question\":\"你想用哪种语言？\","
                                + "\"options\":[{\"id\":\"python\",\"label\":\"Python\"}]}");
                    }
                    return new Reply(text, "");
                }
            }, pool, true);

            WorkflowRunDetail started = harness.runs.trial(request(harness.releaseId));
            Long runId = started.getRun().getId();
            awaitStatus(harness, runId, "WAITING_REPLY");

            // 续跑必须在引擎还没跑完时返回：返回的是同一条 run，状态已回到运行中
            WorkflowRunDetail continued = assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> harness.replies.reply(runId, "Python"));
            assertEquals(runId, continued.getRun().getId());
            assertEquals("RUNNING", continued.getRun().getStatus());
            assertEquals("TRIAL", continued.getRun().getTriggerType());
            assertNull(continued.getRun().getWorkbenchSessionId());
            assertTrue(secondEntered.await(3, TimeUnit.SECONDS), "续跑没有在后台开始");

            release.countDown();
            awaitStatus(harness, runId, "SUCCESS");
            // 仍是同一条 run，不新建
            assertEquals(1, harness.store.listRuns(harness.workflowId, 10).size());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void fullQueueFailsAResumedRunInsteadOfLeavingItRunning() {
        Harness harness = harness((agentId, message, userId, runId) -> "不会执行", Runnable::run, false);
        WorkflowRun resumed = new WorkflowRun();
        resumed.setTenantId(1L);
        resumed.setWorkflowId(harness.workflowId);
        resumed.setStatus("RUNNING");
        resumed.setTriggerType("TRIAL");
        resumed.setTriggeredBy(7L);
        resumed.setGraphJson(harness.graph);
        Long runId = harness.store.insertRun(resumed);

        WorkflowTrialDispatcher rejecting = new WorkflowTrialDispatcher(harness.engine, harness.store.runs(),
                command -> {
                    throw new RejectedExecutionException("full");
                });
        rejecting.submit(runId, 7L, 1L);

        WorkflowRun stored = harness.store.run(runId);
        assertEquals("FAILED", stored.getStatus());
        assertTrue(stored.getErrorSummary().contains("调试队列已满"));
    }

    private WorkflowRunTrialReq request(Long releaseId) {
        WorkflowRunTrialReq req = new WorkflowRunTrialReq();
        req.setReleaseId(releaseId);
        ObjectNode input = json.createObjectNode();
        input.put("query", "你好");
        req.setInput(input);
        return req;
    }

    private Harness harness(WorkflowAgentInvoker invoker, java.util.concurrent.Executor executor,
                            boolean awaitReply) {
        InMemoryWorkflowStore store = new InMemoryWorkflowStore();
        WorkflowGraphCodec codec = new WorkflowGraphCodec(json);
        WorkflowProperties properties = new WorkflowProperties();
        String graph = WorkflowTestGraphs.graph(
                WorkflowTestGraphs.node("n1", "start", "START", "{}") + ","
                        + WorkflowTestGraphs.node("n2", "ask", "AGENT",
                        awaitReply ? "{\"agentId\":1,\"awaitReply\":true}" : "{\"agentId\":1}") + ","
                        // 询问后的后续节点：续跑必须真的继续往下跑，才能验证「请求先返回、引擎在后台」
                        + WorkflowTestGraphs.node("n3", "answer", "AGENT", "{\"agentId\":1}") + ","
                        + WorkflowTestGraphs.node("n4", "end", "END", "{}"),
                WorkflowTestGraphs.edge("e1", "n1", "n2", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e2", "n2", "n3", "NORMAL", null) + ","
                        + WorkflowTestGraphs.edge("e3", "n3", "n4", "NORMAL", null));
        WorkflowDef def = new WorkflowDef();
        def.setId(10L);
        def.setTenantId(1L);
        def.setCode("debug_demo");
        def.setName("调试演示");
        store.putDef(def);
        WorkflowRelease release = new WorkflowRelease();
        release.setId(20L);
        release.setWorkflowId(10L);
        release.setVersion(1);
        release.setGraphJson(graph);
        release.setFingerprint("fp");
        FixedReleaseRepository releases = new FixedReleaseRepository(release);
        WorkflowEngine engine = new WorkflowEngine(store.runs(), store.nodeRuns(), store.humanTasks(),
                invoker, (toolId, args) -> "工具结果", codec, properties, json, new RecordingTraceWriter());
        WorkflowRunAssembler assembler = new WorkflowRunAssembler(store.nodeRuns(), store.humanTasks());
        OperatorContext operator = new OperatorStub();
        WorkflowHumanTaskAppService human = new WorkflowHumanTaskAppService(store.humanTasks(), store.nodeRuns(),
                store.runs(), engine, codec, assembler, () -> 1L, operator,
                (module, action, targetType, targetId, summary) -> {
                }, store.defs(), json, new RecordingTraceWriter(), new RecordingWorkbenchTimeline());
        WorkflowTrialDispatcher dispatcher = new WorkflowTrialDispatcher(engine, store.runs(), executor);
        WorkflowRunAppService runs = new WorkflowRunAppService(store.runs(), store.nodeRuns(), store.humanTasks(),
                releases, store.defs(), () -> 1L, operator, codec, engine, assembler, human,
                (module, action, targetType, targetId, summary) -> {
                }, properties, json, new RecordingTraceWriter(), dispatcher,
                new EmptyDraftRepository(), null);
        WorkflowTrialReplyAppService replies = new WorkflowTrialReplyAppService(
                store.runs(), runs, () -> 1L, operator);
        return new Harness(store, runs, replies, engine, 10L, 20L, graph);
    }

    /**
     * 轮询内存库直到 run 进入指定状态；超时按失败处理，避免测试挂在别的线程上。
     *
     * @param harness  测试夹具（读写同一份内存库）
     * @param runId    run 主键
     * @param expected 期望状态
     */
    private static void awaitStatus(Harness harness, Long runId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String last = null;
        while (System.nanoTime() < deadline) {
            WorkflowRun current = harness.store.run(runId);
            last = current == null ? null : current.getStatus();
            if (expected.equalsIgnoreCase(last)) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, last, "运行没有进入预期状态");
    }

    private record Harness(InMemoryWorkflowStore store, WorkflowRunAppService runs,
                           WorkflowTrialReplyAppService replies, WorkflowEngine engine,
                           Long workflowId, Long releaseId, String graph) {
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

    /** 只回答 findById 的发布版本仓储。 */
    private static final class FixedReleaseRepository implements WorkflowReleaseRepository {

        private final WorkflowRelease release;

        private FixedReleaseRepository(WorkflowRelease release) {
            this.release = release;
        }

        @Override
        public int maxVersion(Long workflowId) {
            return release.getVersion() == null ? 0 : release.getVersion();
        }

        @Override
        public Long insert(WorkflowRelease ignored) {
            return release.getId();
        }

        @Override
        public Optional<WorkflowRelease> findById(Long id) {
            return release.getId().equals(id) ? Optional.of(release) : Optional.empty();
        }

        @Override
        public List<WorkflowRelease> listByWorkflowId(Long workflowId) {
            return release.getWorkflowId().equals(workflowId) ? List.of(release) : List.of();
        }
    }
}

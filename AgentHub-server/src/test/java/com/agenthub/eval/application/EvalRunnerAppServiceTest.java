package com.agenthub.eval.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.eval.api.dto.EvalResultPageReq;
import com.agenthub.eval.api.dto.EvalResultResp;
import com.agenthub.eval.api.dto.EvalRunCreateReq;
import com.agenthub.eval.api.dto.EvalRunResp;
import com.agenthub.eval.api.dto.EvalSuiteCreateReq;
import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalTrialRequest;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.domain.model.OpenJudgeJudge;
import com.agenthub.eval.domain.model.OpenJudgeRequest;
import com.agenthub.eval.domain.model.OpenJudgeScoreResult;
import com.agenthub.eval.domain.model.OpenJudgeStatus;
import com.agenthub.eval.domain.service.JudgeModelResolver;
import com.agenthub.eval.domain.service.JudgeModelUnavailableException;
import com.agenthub.eval.domain.service.OpenJudgeScorerPort;
import com.agenthub.eval.domain.service.TrialEvalPort;
import com.agenthub.eval.infra.EvalProperties;
import com.agenthub.eval.support.InMemoryEvalCaseRepository;
import com.agenthub.eval.support.InMemoryEvalResultRepository;
import com.agenthub.eval.support.InMemoryEvalRunRepository;
import com.agenthub.eval.support.InMemoryEvalSuiteRepository;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.runtime.domain.model.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvalRunner + TrialEvalPort（mock）+ RuleScorer 接线。所属域：eval。
 * <p>验收强制 1-case suite；不连库、不起服务、不出站、不真打模型。
 */
class EvalRunnerAppServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final InMemoryEvalSuiteRepository suites = new InMemoryEvalSuiteRepository();
    private final InMemoryEvalCaseRepository cases = new InMemoryEvalCaseRepository();
    private final InMemoryEvalRunRepository runs = new InMemoryEvalRunRepository();
    private final InMemoryEvalResultRepository results = new InMemoryEvalResultRepository();
    private final OperLogWriter operLog = mock(OperLogWriter.class);
    private final TrialEvalPort trialPort = mock(TrialEvalPort.class);
    private final OpenJudgeScorerPort openJudgePort = mock(OpenJudgeScorerPort.class);
    private final AgentDefRepository agents = mock(AgentDefRepository.class);
    private final ModelInstanceRepository modelInstances = mock(ModelInstanceRepository.class);
    /** 打分模型凭据解析：默认返回一份假凭据，保证「选了模型」路径能动 */
    private final JudgeModelResolver judgeResolver = mock(JudgeModelResolver.class);
    /** 打分模型 id：本测试固定一个合法 CHAT 实例 */
    private static final Long JUDGE_MODEL_ID = 501L;
    /** 假明文 Key：只用于断言「进了请求体、没进落库/日志」 */
    private static final String FAKE_JUDGE_KEY = "sk-test-fake-judge-key-0001";
    private final EvalRunFlight flight = new EvalRunFlight();

    @TempDir
    Path tempWorkspace;

    private long currentTenant = 1L;
    private EvalRunAppService runService;
    private EvalRunnerAppService runner;
    private EvalProperties properties;
    private Long suiteId;
    private Long agentId = 7L;

    @BeforeEach
    void setUp() {
        properties = new EvalProperties();
        properties.getReport().setWorkspaceDir(tempWorkspace.toString());
        properties.getReport().setWriteWorkspace(true);
        properties.getReport().setMarkdown(true);
        properties.getOpenjudge().setEnabled(false);

        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(100L);
        when(operator.userIdOrNull()).thenReturn(100L);

        EvalAssembler assembler = new EvalAssembler(json);
        CaseStoreAppService caseStore = new CaseStoreAppService(suites, cases, assembler,
                new EvalSampleCatalog(new DefaultResourceLoader(), json, properties),
                () -> currentTenant, operator, operLog, json);

        ReportBuilder reportBuilder = new ReportBuilder(json, properties);
        runner = new EvalRunnerAppService(runs, results, cases, agents, trialPort, openJudgePort,
                reportBuilder, flight, assembler, () -> currentTenant, operator, operLog, properties, json,
                judgeResolver);
        runService = new EvalRunAppService(suites, cases, runs, results, assembler,
                () -> currentTenant, operator, operLog, json, runner, properties, modelInstances);

        suiteId = caseStore.create(oneCaseSuite("t.runner")).getId();
        when(agents.findById(agentId)).thenReturn(Optional.of(enabledAgent(agentId)));
        // 打分模型：只有显式选中且启用的 CHAT 实例才允许建跑
        when(modelInstances.findById(JUDGE_MODEL_ID))
                .thenReturn(Optional.of(chatJudgeModel(JUDGE_MODEL_ID)));
        // 默认 mock：若误开 openjudge 也不出站
        when(openJudgePort.score(any())).thenReturn(OpenJudgeScoreResult.of(OpenJudgeStatus.DISABLED, "test"));
        // 默认凭据：真实 Key 由模型中心解密，单测用假 Key；断言它只出现在请求里
        when(judgeResolver.resolve(JUDGE_MODEL_ID)).thenReturn(
                new OpenJudgeJudge("qwen-plus", "https://example.invalid/compatible-mode/v1", FAKE_JUDGE_KEY));
    }

    /**
     * 1-case 成功 + 断言命中：PENDING→SUCCEEDED；PASS；rule_passed=1；gate_passed=1；
     * openjudge=DISABLED；assertion_details / scores.rule 有值。
     */
    @Test
    void executeOneCaseSuccessRulePassAndGatePassed() {
        when(trialPort.runCase(any())).thenReturn(okArtifact("88", "trace-1"));

        Long suiteWithAssert = createSuiteWithAssertions("t.pass",
                json.createArrayNode().add(json.createObjectNode()
                        .put("type", "final_contains")
                        .set("args", json.createObjectNode().put("text", "答案"))));
        EvalRunResp created = runService.createRun(runReq(suiteWithAssert));
        EvalRunResp finished = runner.execute(created.getId());

        assertEquals("SUCCEEDED", finished.getStatus());
        assertEquals(1, finished.getPassed());
        assertEquals(0, finished.getFailed());
        assertEquals(0, finished.getErrored());
        assertEquals(1, finished.getGatePassed());
        assertNotNull(finished.getAgentSnapshotHash());

        List<EvalResultResp> rows = runService.listResults(created.getId(), pageReq()).getRecords();
        assertEquals(1, rows.size());
        EvalResultResp one = rows.get(0);
        assertEquals("PASS", one.getVerdict());
        assertEquals(1, one.getRulePassed());
        assertEquals("88", one.getSessionId());
        assertEquals("trace-1", one.getTraceId());
        assertEquals("DISABLED", one.getOpenjudgeStatus());
        assertNotNull(one.getAssertionDetails());
        assertTrue(one.getAssertionDetails().isArray());
        assertTrue(one.getAssertionDetails().size() > 0);
        assertNotNull(one.getScores());
        assertTrue(one.getScores().has("rule"));
        assertFalse(one.getScores().has("openjudge"));
        assertNotNull(one.getArtifactSummary());
        assertNotNull(finished.getReport(), "关 sidecar 仍须有规则报告");
        assertTrue(finished.getReport().has("gate_passed") || finished.getReport().has("run"));
        verify(openJudgePort, never()).score(any());

        ArgumentCaptor<EvalTrialRequest> captor = ArgumentCaptor.forClass(EvalTrialRequest.class);
        verify(trialPort, times(1)).runCase(captor.capture());
        assertEquals("问题 c0", captor.getValue().getMessage());
        assertEquals("bypass", captor.getValue().getCachePolicy());
    }

    /**
     * OpenJudge enabled + mock OK：scores.openjudge 有分；gate 仍只看规则；verdict 不变。
     */
    @Test
    void openJudgeOkMergesScoresWithoutFlippingGate() {
        properties.getOpenjudge().setEnabled(true);
        when(trialPort.runCase(any())).thenReturn(okArtifact("1", "t"));
        OpenJudgeScoreResult oj = new OpenJudgeScoreResult();
        oj.setStatus(OpenJudgeStatus.OK);
        oj.setScore(4.5);
        oj.setReason("相关");
        oj.setRawJson("{\"score\":4.5}");
        when(openJudgePort.score(any())).thenReturn(oj);

        EvalRunCreateReq req = runReq(suiteId);
        req.setConfigOverride(openjudgeOverride());
        EvalRunResp created = runService.createRun(req);
        EvalRunResp finished = runner.execute(created.getId());

        assertEquals(1, finished.getGatePassed());
        assertEquals("SUCCEEDED", finished.getStatus());
        EvalResultResp one = runService.listResults(created.getId(), pageReq()).getRecords().get(0);
        assertEquals("PASS", one.getVerdict());
        assertEquals("OK", one.getOpenjudgeStatus());
        assertNotNull(one.getScores().get("rule"));
        assertEquals(4.5, one.getScores().get("openjudge").get("score").asDouble(), 0.01);
        // 选了模型：评分请求必须带上该实例 id
        ArgumentCaptor<OpenJudgeRequest> ojCaptor = ArgumentCaptor.forClass(OpenJudgeRequest.class);
        verify(openJudgePort, times(1)).score(ojCaptor.capture());
        assertEquals(JUDGE_MODEL_ID, ojCaptor.getValue().getJudgeModelInstanceId());
        // 凭据：Java 解密后随请求体环回透传（model / base_url / api_key）
        OpenJudgeJudge judge = ojCaptor.getValue().getJudge();
        assertNotNull(judge, "选了打分模型必须带上凭据");
        assertEquals("qwen-plus", judge.getModel());
        assertEquals("https://example.invalid/compatible-mode/v1", judge.getBaseUrl());
        assertEquals(FAKE_JUDGE_KEY, judge.getApiKey());
        // 凭据对象的 toString 必须脱敏，防止误打日志泄 Key
        assertFalse(judge.toString().contains(FAKE_JUDGE_KEY));
    }

    /**
     * 回归：case 里带着历史 {@code scoring.openjudge.enabled=false}（样例复制而来）时，
     * 建跑显式选了打分模型仍必须出站 —— 否则列表 Judge 列会全部显示「未启用」。
     */
    @Test
    void caseLevelOpenjudgeOffDoesNotVetoSelectedJudgeModel() {
        when(trialPort.runCase(any())).thenReturn(okArtifact("1", "t"));
        when(openJudgePort.score(any())).thenReturn(OpenJudgeScoreResult.of(OpenJudgeStatus.OK, "ok"));

        ObjectNode scoring = json.createObjectNode()
                .set("openjudge", json.createObjectNode().put("enabled", false));
        Long suiteWithCaseOff = createSuiteWithScoring("t.case.oj.off", scoring);

        EvalRunCreateReq req = runReq(suiteWithCaseOff);
        req.setJudgeModelInstanceId(JUDGE_MODEL_ID);
        EvalRunResp created = runService.createRun(req);
        EvalRunResp finished = runner.execute(created.getId());

        assertEquals("SUCCEEDED", finished.getStatus());
        EvalResultResp one = runService.listResults(created.getId(), pageReq()).getRecords().get(0);
        assertEquals("OK", one.getOpenjudgeStatus(), "run 级已选模型，case 级 false 不得否决");
        verify(openJudgePort, times(1)).score(any());
    }

    /**
     * 凭据不进落库/报告：sidecar 若把请求体回显进 reason/raw，落库前必须抹成 ***。
     */
    @Test
    void judgeCredentialNeverPersistedEvenIfSidecarEchoesIt() {
        properties.getOpenjudge().setEnabled(true);
        when(trialPort.runCase(any())).thenReturn(okArtifact("1", "t"));
        OpenJudgeScoreResult oj = new OpenJudgeScoreResult();
        oj.setStatus(OpenJudgeStatus.OK);
        oj.setScore(4.0);
        // 故意模拟 sidecar 回显请求体（含 Key）
        oj.setReason("api_key=" + FAKE_JUDGE_KEY + " 评分完成");
        oj.setRawJson("{\"echo\":{\"api_key\":\"" + FAKE_JUDGE_KEY + "\"},\"score\":4.0}");
        when(openJudgePort.score(any())).thenReturn(oj);

        EvalRunCreateReq req = runReq(suiteId);
        req.setConfigOverride(openjudgeOverride());
        EvalRunResp finished = runner.execute(runService.createRun(req).getId());

        EvalResult stored = results.listByRun(finished.getId()).get(0);
        assertFalse(String.valueOf(stored.getOpenjudgeRawJson()).contains(FAKE_JUDGE_KEY),
                "openjudge_raw_json 不得含明文 Key");
        assertFalse(String.valueOf(stored.getScoresJson()).contains(FAKE_JUDGE_KEY),
                "scores_json 不得含明文 Key");
        assertTrue(String.valueOf(stored.getScoresJson()).contains("***"), "应留下脱敏痕迹");
        assertFalse(String.valueOf(finished.getReport()).contains(FAKE_JUDGE_KEY),
                "报告不得含明文 Key");
        assertEquals(1, finished.getGatePassed(), "脱敏不影响 gate；gate 仍只看规则");
    }

    /**
     * 凭据解不出（实例停用 / 解密失败）：本 case ERROR、零 Judge 出站、
     * verdict/gate 不变，且不得回落侧车环境变量 Key 冒充所选实例。
     */
    @Test
    void judgeCredentialUnavailableMarksErrorWithoutCallingSidecar() {
        properties.getOpenjudge().setEnabled(true);
        when(trialPort.runCase(any())).thenReturn(okArtifact("1", "t"));
        when(judgeResolver.resolve(JUDGE_MODEL_ID))
                .thenThrow(new JudgeModelUnavailableException("打分模型凭据解密失败，请到模型管理重置 Key：501"));

        EvalRunCreateReq req = runReq(suiteId);
        req.setConfigOverride(openjudgeOverride());
        EvalRunResp finished = runner.execute(runService.createRun(req).getId());

        // 软通道失败不改规则结论
        assertEquals(1, finished.getGatePassed());
        EvalResultResp one = runService.listResults(finished.getId(), pageReq()).getRecords().get(0);
        assertEquals("PASS", one.getVerdict());
        assertEquals("ERROR", one.getOpenjudgeStatus());
        assertEquals("JUDGE_CREDENTIAL_UNAVAILABLE", one.getScores().get("openjudge").get("error").asText());
        assertFalse(String.valueOf(one.getScores()).contains("sk-"), "原因里不得出现 Key 片段");
        verify(openJudgePort, never()).score(any());
    }

    /**
     * 只开 enabled=true、没有打分模型：本 run 强制 DISABLED，零 Judge 出站。
     */
    @Test
    void openjudgeEnabledWithoutJudgeModelStaysDisabled() {
        properties.getOpenjudge().setEnabled(true);
        when(trialPort.runCase(any())).thenReturn(okArtifact("1", "t"));

        EvalRunCreateReq req = runReq(suiteId);
        req.setConfigOverride(json.createObjectNode().set("openjudge",
                json.createObjectNode().put("enabled", true)));
        EvalRunResp finished = runner.execute(runService.createRun(req).getId());

        assertEquals(1, finished.getGatePassed());
        assertEquals(0, finished.getOpenjudgeEnabled(), "无模型不得启用软评分");
        EvalResultResp one = runService.listResults(finished.getId(), pageReq()).getRecords().get(0);
        assertEquals("DISABLED", one.getOpenjudgeStatus());
        assertFalse(one.getScores().has("openjudge"));
        verify(openJudgePort, never()).score(any());
    }

    /**
     * 规则 FAIL + OpenJudge OK：verdict=FAIL，gate=0，openjudge=OK（诊断分仍可有）。
     */
    @Test
    void ruleFailWithOpenJudgeOkKeepsFailAndGateFalse() {
        properties.getOpenjudge().setEnabled(true);
        when(trialPort.runCase(any())).thenReturn(okArtifact("1", "t"));
        OpenJudgeScoreResult oj = new OpenJudgeScoreResult();
        oj.setStatus(OpenJudgeStatus.OK);
        oj.setScore(5.0);
        oj.setRawJson("{\"score\":5}");
        when(openJudgePort.score(any())).thenReturn(oj);

        Long suiteWithAssert = createSuiteWithAssertions("t.fail.oj",
                json.createArrayNode().add(json.createObjectNode()
                        .put("type", "final_contains")
                        .set("args", json.createObjectNode().put("text", "绝对不命中"))));
        EvalRunCreateReq req = runReq(suiteWithAssert);
        req.setConfigOverride(openjudgeOverride());
        EvalRunResp finished = runner.execute(runService.createRun(req).getId());

        assertEquals(0, finished.getGatePassed());
        EvalResultResp one = runService.listResults(finished.getId(), pageReq()).getRecords().get(0);
        assertEquals("FAIL", one.getVerdict());
        assertEquals("OK", one.getOpenjudgeStatus());
        assertEquals(5.0, one.getScores().get("openjudge").get("score").asDouble(), 0.01);
    }

    /**
     * OpenJudge 连接失败/ERROR：verdict 仍为规则 PASS；gate 不变；status=ERROR。
     */
    @Test
    void openJudgeErrorDoesNotChangeVerdictOrGate() {
        when(trialPort.runCase(any())).thenReturn(okArtifact("1", "t"));
        when(openJudgePort.score(any())).thenReturn(
                OpenJudgeScoreResult.of(OpenJudgeStatus.ERROR, "sidecar 超时"));

        EvalRunCreateReq req = runReq(suiteId);
        req.setConfigOverride(openjudgeOverride());
        EvalRunResp finished = runner.execute(runService.createRun(req).getId());

        assertEquals(1, finished.getGatePassed());
        EvalResultResp one = runService.listResults(finished.getId(), pageReq()).getRecords().get(0);
        assertEquals("PASS", one.getVerdict());
        assertEquals("ERROR", one.getOpenjudgeStatus());
        assertNotEquals("FAIL", one.getOpenjudgeStatus());
    }

    /**
     * 故意写错期望 → verdict=FAIL；gate_passed=0；run 仍 SUCCEEDED（跑完了）。
     */
    @Test
    void executeRuleFailSetsVerdictFailAndGateFalse() {
        when(trialPort.runCase(any())).thenReturn(okArtifact("1", "t"));

        Long suiteWithAssert = createSuiteWithAssertions("t.fail",
                json.createArrayNode().add(json.createObjectNode()
                        .put("type", "final_contains")
                        .set("args", json.createObjectNode().put("text", "绝对不命中"))));
        EvalRunResp created = runService.createRun(runReq(suiteWithAssert));
        EvalRunResp finished = runner.execute(created.getId());

        assertEquals("SUCCEEDED", finished.getStatus());
        assertEquals(0, finished.getPassed());
        assertEquals(1, finished.getFailed());
        assertEquals(0, finished.getGatePassed());
        EvalResultResp one = runService.listResults(created.getId(), pageReq()).getRecords().get(0);
        assertEquals("FAIL", one.getVerdict());
        assertEquals(0, one.getRulePassed());
        assertEquals("DISABLED", one.getOpenjudgeStatus());
        assertNotNull(one.getAssertionDetails());
        verify(openJudgePort, never()).score(any());
    }

    /**
     * trial 抛错 → verdict=ERROR；rule_passed null；不被改成 FAIL；gate_passed=0。
     */
    @Test
    void executeTrialErrorStaysErrorNotFail() {
        when(trialPort.runCase(any())).thenThrow(new BizException(ErrorCode.BAD_REQUEST, "模型拒绝"));

        EvalRunResp created = runService.createRun(runReq(suiteId));
        EvalRunResp finished = runner.execute(created.getId());

        assertEquals("FAILED", finished.getStatus());
        assertEquals(1, finished.getErrored());
        assertEquals(0, finished.getPassed());
        assertEquals(0, finished.getFailed());
        assertEquals(0, finished.getGatePassed());
        EvalResultResp one = runService.listResults(created.getId(), pageReq()).getRecords().get(0);
        assertEquals("ERROR", one.getVerdict());
        assertNull(one.getRulePassed());
        assertNull(one.getAssertionDetails());
        assertEquals("SKIPPED", one.getOpenjudgeStatus(), "trial 失败未评 → SKIPPED");
        verify(openJudgePort, never()).score(any());
    }

    /**
     * continue_on_case_error=false：第一 case ERROR 后剩余 SKIPPED（本测用 2-case 专验分支）。
     */
    @Test
    void continueOnCaseErrorFalseAbortsRemainingAsSkipped() {
        Long twoCaseSuite = new CaseStoreAppService(suites, cases, new EvalAssembler(json),
                new EvalSampleCatalog(new DefaultResourceLoader(), json, new EvalProperties()),
                () -> currentTenant, mockOperator(), operLog, json)
                .create(twoCaseSuite("t.abort")).getId();

        AtomicInteger calls = new AtomicInteger();
        when(trialPort.runCase(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "第一 case 炸");
            }
            return okArtifact("9", "t");
        });

        EvalRunCreateReq req = runReq(twoCaseSuite);
        req.setConfigOverride(json.createObjectNode().put("continue_on_case_error", false));
        EvalRunResp created = runService.createRun(req);
        EvalRunResp finished = runner.execute(created.getId());

        assertEquals(1, calls.get(), "第二 case 不得再调 Trial");
        assertEquals("FAILED", finished.getStatus());
        assertEquals(1, finished.getErrored());
        assertEquals(1, finished.getSkipped());
        List<String> verdicts = runService.listResults(created.getId(), pageReq()).getRecords()
                .stream().map(EvalResultResp::getVerdict).toList();
        assertEquals(List.of("ERROR", "SKIPPED"), verdicts);
    }

    /**
     * 有 PASS 又有 ERROR → PARTIAL。
     */
    @Test
    void executeWithPassAndErrorEndsPartial() {
        Long twoCaseSuite = new CaseStoreAppService(suites, cases, new EvalAssembler(json),
                new EvalSampleCatalog(new DefaultResourceLoader(), json, new EvalProperties()),
                () -> currentTenant, mockOperator(), operLog, json)
                .create(twoCaseSuite("t.partial")).getId();

        AtomicInteger calls = new AtomicInteger();
        when(trialPort.runCase(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                return okArtifact("1", "t1");
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "第二 case 炸");
        });

        EvalRunResp created = runService.createRun(runReq(twoCaseSuite));
        EvalRunResp finished = runner.execute(created.getId());

        assertEquals("PARTIAL", finished.getStatus());
        assertEquals(1, finished.getPassed());
        assertEquals(1, finished.getErrored());
        assertEquals(0, finished.getGatePassed());
    }

    /**
     * 幂等：PENDING 双 execute → 第二次冲突；port 只调一次。
     */
    @Test
    void secondExecuteWhileRunningOrDoneIsConflict() {
        when(trialPort.runCase(any())).thenReturn(okArtifact("1", "t"));
        EvalRunResp created = runService.createRun(runReq(suiteId));
        runner.execute(created.getId());

        BizException ex = assertThrows(BizException.class, () -> runner.execute(created.getId()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已结束") || ex.getMessage().contains("执行中"));
        verify(trialPort, times(1)).runCase(any());
    }

    /**
     * cancel RUNNING：调用 port.cancelSession；余 case SKIPPED；终态 FAILED（无 PASS）。
     */
    @Test
    void cancelRunningCascadesTrialCancel() {
        // 卡住 runCase：先 bind flight，再抛错模拟「取消打断」
        when(trialPort.runCase(any())).thenAnswer(inv -> {
            EvalTrialRequest req = inv.getArgument(0);
            flight.bind(req.getEvalRunId(), "55");
            // 不真正阻塞：直接返回成功前由测试侧先 cancel 不合理；
            // 改测：先建 RUNNING 并 bind，再 cancel 验证 cancelSession
            return okArtifact("55", "t");
        });

        // 更干净：不走 execute，直接把 run 推到 RUNNING + bind，再 cancel
        EvalRunResp created = runService.createRun(runReq(suiteId));
        var run = runs.findById(created.getId()).orElseThrow();
        com.agenthub.eval.domain.service.EvalRunStateMachine.markRunning(run);
        runs.updateState(run, 100L);
        flight.bind(created.getId(), "55");

        EvalRunResp canceled = runService.cancel(created.getId());

        verify(trialPort, atLeastOnce()).cancelSession("55");
        assertEquals("FAILED", canceled.getStatus());
        assertEquals(1, canceled.getSkipped());
        assertEquals("SKIPPED", runService.listResults(created.getId(), pageReq()).getRecords().get(0).getVerdict());
    }

    /**
     * Agent 缺失 → 起步 FAILED，不调 Trial，不留 RUNNING。
     */
    @Test
    void missingAgentFailsBeforeStart() {
        when(agents.findById(agentId)).thenReturn(Optional.empty());
        EvalRunResp created = runService.createRun(runReq(suiteId));

        EvalRunResp finished = runner.execute(created.getId());

        assertEquals("FAILED", finished.getStatus());
        assertTrue(finished.getErrorMessage().contains("不存在"));
        verify(trialPort, never()).runCase(any());
        assertNull(runService.listResults(created.getId(), pageReq()).getRecords().get(0).getVerdict());
    }

    /**
     * 租户隔离：B 不能 execute A 的 run。
     */
    @Test
    void otherTenantCannotExecute() {
        EvalRunResp created = runService.createRun(runReq(suiteId));
        currentTenant = 2L;
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> runner.execute(created.getId())).getErrorCode());
        verify(trialPort, never()).runCase(any());
    }

    // ------------------------------------------------------------------

    private OperatorContext mockOperator() {
        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(100L);
        when(operator.userIdOrNull()).thenReturn(100L);
        return operator;
    }

    private EvalResultPageReq pageReq() {
        EvalResultPageReq req = new EvalResultPageReq();
        req.setSize(50);
        return req;
    }

    private EvalRunCreateReq runReq(Long suiteId) {
        EvalRunCreateReq req = new EvalRunCreateReq();
        req.setSuiteId(suiteId);
        req.setAgentId(agentId);
        return req;
    }

    /**
     * 本 run 选择打分模型的覆盖配置：开开关 + 指定合法 CHAT 实例。
     *
     * @return openjudge 覆盖节点
     */
    private ObjectNode openjudgeOverride() {
        ObjectNode openjudge = json.createObjectNode();
        openjudge.put("enabled", true);
        openjudge.put("judge_model_instance_id", JUDGE_MODEL_ID);
        return json.createObjectNode().set("openjudge", openjudge);
    }

    /**
     * 合法打分模型：启用中的 CHAT 实例。
     *
     * @param id 实例 id
     * @return 实例
     */
    private ModelInstance chatJudgeModel(Long id) {
        ModelInstance one = new ModelInstance();
        one.setId(id);
        one.setStatus(1);
        one.setCapability("CHAT");
        one.setDisplayName("打分模型 " + id);
        return one;
    }

    private EvalSuiteCreateReq oneCaseSuite(String key) {
        EvalSuiteCreateReq req = new EvalSuiteCreateReq();
        req.setSuiteKey(key);
        req.setName("单 case 套件");
        req.setCases(List.of(caseSpec("c0", 0)));
        return req;
    }

    private EvalSuiteCreateReq twoCaseSuite(String key) {
        EvalSuiteCreateReq req = new EvalSuiteCreateReq();
        req.setSuiteKey(key);
        req.setName("双 case 套件");
        req.setCases(List.of(caseSpec("c0", 0), caseSpec("c1", 10)));
        return req;
    }

    private EvalCaseSpec caseSpec(String key, int sort) {
        EvalCaseSpec one = new EvalCaseSpec();
        one.setCaseKey(key);
        one.setTitle("用例 " + key);
        one.setInput(json.createObjectNode().put("message", "问题 " + key));
        one.setSortOrder(sort);
        one.setEnabled(1);
        return one;
    }

    private Long createSuiteWithAssertions(String suiteKey, com.fasterxml.jackson.databind.JsonNode assertions) {
        EvalSuiteCreateReq req = new EvalSuiteCreateReq();
        req.setSuiteKey(suiteKey);
        req.setName("带断言套件");
        EvalCaseSpec one = caseSpec("c0", 0);
        one.setAssertions(assertions);
        req.setCases(List.of(one));
        return new CaseStoreAppService(suites, cases, new EvalAssembler(json),
                new EvalSampleCatalog(new DefaultResourceLoader(), json, new EvalProperties()),
                () -> currentTenant, mockOperator(), operLog, json)
                .create(req).getId();
    }

    /**
     * 建一个单 case 套件，并给该 case 写指定 scoring（用于复现历史 case 级打分开关）。
     *
     * @param suiteKey 套件键
     * @param scoring  case.scoring JSON
     * @return 套件主键
     */
    private Long createSuiteWithScoring(String suiteKey, com.fasterxml.jackson.databind.JsonNode scoring) {
        EvalSuiteCreateReq req = new EvalSuiteCreateReq();
        req.setSuiteKey(suiteKey);
        req.setName("带 scoring 套件");
        EvalCaseSpec one = caseSpec("c0", 0);
        one.setScoring(scoring);
        req.setCases(List.of(one));
        return new CaseStoreAppService(suites, cases, new EvalAssembler(json),
                new EvalSampleCatalog(new DefaultResourceLoader(), json, new EvalProperties()),
                () -> currentTenant, mockOperator(), operLog, json)
                .create(req).getId();
    }

    private AgentDef enabledAgent(Long id) {
        AgentDef agent = new AgentDef();
        agent.setId(id);
        agent.setName("评测 Agent");
        agent.setStatus(AgentStatus.ENABLED);
        agent.setRunMode("REACT");
        agent.setUpdateTime(LocalDateTime.of(2026, 11, 3, 12, 0));
        agent.setRuntimeConfigJson("{\"x\":1}");
        return agent;
    }

    private EvalArtifact okArtifact(String sessionId, String traceId) {
        EvalArtifact artifact = new EvalArtifact();
        artifact.setSessionId(sessionId);
        artifact.setTraceId(traceId);
        artifact.setStatus(RunStatus.SUCCESS.name());
        artifact.setFinalAnswer("答案");
        artifact.setCacheHit(false);
        artifact.setLatencyMs(12);
        return artifact;
    }
}

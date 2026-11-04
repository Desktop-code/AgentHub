package com.agenthub.eval.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.eval.api.dto.EvalRunResp;
import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.EvalCase;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalRunStatus;
import com.agenthub.eval.domain.model.EvalTrialRequest;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.domain.model.OpenJudgeRequest;
import com.agenthub.eval.domain.model.OpenJudgeScoreResult;
import com.agenthub.eval.domain.model.OpenJudgeStatus;
import com.agenthub.eval.domain.model.RuleScoreResult;
import com.agenthub.eval.domain.repository.EvalCaseRepository;
import com.agenthub.eval.domain.repository.EvalResultRepository;
import com.agenthub.eval.domain.repository.EvalRunRepository;
import com.agenthub.eval.domain.service.EvalAgentSnapshot;
import com.agenthub.eval.domain.service.EvalRunConfig;
import com.agenthub.eval.domain.service.EvalRunStateMachine;
import com.agenthub.eval.domain.service.EvalSpecs;
import com.agenthub.eval.domain.service.EvalTenantProvider;
import com.agenthub.eval.domain.service.OpenJudgeScorerPort;
import com.agenthub.eval.domain.service.RuleScorer;
import com.agenthub.eval.domain.service.TrialEvalPort;
import com.agenthub.eval.infra.EvalProperties;
import com.agenthub.eval.infra.openjudge.OpenJudgeScorerAdapter;
import com.agenthub.runtime.domain.model.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测 Runner。所属域：eval 的 application。
 * <p>职责：PENDING→RUNNING → {@link TrialEvalPort} → {@link RuleScorer} →（可选）{@link OpenJudgeScorerPort}
 * → 收束终态写 {@code gate_passed} → {@link ReportBuilder}。
 * <p><b>verdict / gate</b>：只由规则（及 trial 基础设施 ERROR）决定；OpenJudge <b>绝不</b>翻转 gate_passed，
 * 也绝不把 case 打成 FAIL。
 * <p>幂等：仅允许一次 PENDING→RUNNING CAS。刻意不做：SSE、菜单、EvalScope、UI。
 */
@Slf4j
@Service
public class EvalRunnerAppService {

    private final EvalRunRepository runs;
    private final EvalResultRepository results;
    private final EvalCaseRepository cases;
    private final AgentDefRepository agents;
    private final TrialEvalPort trialPort;
    private final OpenJudgeScorerPort openJudgePort;
    private final ReportBuilder reportBuilder;
    private final EvalRunFlight flight;
    private final EvalAssembler assembler;
    private final EvalTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final EvalProperties properties;
    private final ObjectMapper json;

    /**
     * @param runs            跑批仓储
     * @param results         结果仓储
     * @param cases           用例仓储
     * @param agents          Agent 只读
     * @param trialPort       试跑防腐口（单测 mock）
     * @param openJudgePort   OpenJudge 软评分（单测 mock）
     * @param reportBuilder   报告构建
     * @param flight          in-flight 会话
     * @param assembler       出参装配
     * @param tenantProvider  租户
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     * @param properties      全局缺省
     * @param json            JSON
     */
    public EvalRunnerAppService(EvalRunRepository runs, EvalResultRepository results, EvalCaseRepository cases,
                                AgentDefRepository agents, TrialEvalPort trialPort,
                                OpenJudgeScorerPort openJudgePort, ReportBuilder reportBuilder,
                                EvalRunFlight flight, EvalAssembler assembler, EvalTenantProvider tenantProvider,
                                OperatorContext operatorContext, OperLogWriter operLogWriter,
                                EvalProperties properties, ObjectMapper json) {
        this.runs = runs;
        this.results = results;
        this.cases = cases;
        this.agents = agents;
        this.trialPort = trialPort;
        this.openJudgePort = openJudgePort;
        this.reportBuilder = reportBuilder;
        this.flight = flight;
        this.assembler = assembler;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.properties = properties;
        this.json = json;
    }

    /**
     * 显式开跑：CAS PENDING→RUNNING 后串行执行，收束终态并生成报告。
     *
     * @param runId 跑批
     * @return 收束后的跑批出参
     */
    public EvalRunResp execute(Long runId) {
        Long operatorId = operatorContext.requireUserId();
        EvalRun run = requireWritable(runId);

        if (run.getStatus() == EvalRunStatus.CANCELING) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测跑批正在取消，不能执行");
        }
        if (run.isTerminal()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评测跑批已结束（" + run.getStatus().getLabel() + "），不能再执行");
        }
        if (run.getStatus() == EvalRunStatus.RUNNING) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测跑批已在执行中，不能重复 execute");
        }
        if (run.getStatus() != EvalRunStatus.PENDING) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法状态，不能执行：" + run.getStatus());
        }
        if (run.getTotalCases() == null || run.getTotalCases() <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无可跑用例");
        }

        AgentDef agent = agents.findById(run.getAgentId()).orElse(null);
        if (agent == null) {
            return failBeforeStart(run, operatorId, "Agent 不存在：" + run.getAgentId());
        }
        if (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED) {
            return failBeforeStart(run, operatorId, "Agent 未启用：" + run.getAgentId());
        }

        JsonNode config = assembler.readNode(run.getConfigJson());
        long timeoutMs = EvalRunConfig.caseTimeoutMs(config, properties.getCaseTimeoutMs());
        boolean continueOnError = EvalRunConfig.continueOnCaseError(config, properties.isContinueOnCaseError());

        run.setAgentSnapshotHash(EvalAgentSnapshot.hash(agent, run.getAgentId()));
        EvalRunStateMachine.markRunning(run);
        if (runs.casUpdateState(run, EvalRunStatus.PENDING, operatorId) == 0) {
            throw new BizException(ErrorCode.DUPLICATE, "评测跑批已被其它请求启动，请刷新后查看");
        }

        Map<Long, EvalCase> caseById = loadCases(run);
        List<EvalResult> all = results.listByRun(run.getId());
        try {
            for (EvalResult stub : all) {
                EvalRun latest = runs.findById(run.getId()).orElse(run);
                if (latest.getStatus() == EvalRunStatus.CANCELING || latest.isTerminal()) {
                    log.info("评测跑批中途取消/终态，停止执行 run={}", runId);
                    break;
                }
                if (!stub.isUnfinished()) {
                    continue;
                }
                runOneCase(run, stub, caseById.get(stub.getCaseId()), config, timeoutMs);

                if (!continueOnError && stub.getVerdict() == EvalVerdict.ERROR) {
                    results.markUnfinished(run.getId(), run.getTenantId(), EvalVerdict.SKIPPED, operatorId);
                    break;
                }
            }
        } finally {
            flight.clear(runId);
        }

        return finishExecute(runId, operatorId);
    }

    /**
     * cancel 级联：若有 in-flight session，经 TrialEvalPort 取消现网会话。
     *
     * @param runId 跑批
     */
    public void cancelInFlightTrial(Long runId) {
        flight.current(runId).ifPresent(sessionId -> {
            try {
                trialPort.cancelSession(sessionId);
            } catch (RuntimeException ex) {
                log.warn("评测级联取消 trial 失败 run={} session={}：{}", runId, sessionId, ex.getMessage());
            } finally {
                flight.clear(runId);
            }
        });
        for (EvalResult one : results.listByRun(runId)) {
            if (one.isUnfinished() && one.getSessionId() != null && !one.getSessionId().isBlank()) {
                try {
                    trialPort.cancelSession(one.getSessionId());
                } catch (RuntimeException ignored) {
                    // 收尾路径不因二次 cancel 失败而中断
                }
            }
        }
    }

    /**
     * 终态后生成/覆盖报告（供 cancel 与 execute 共用）。
     *
     * @param run        已终态跑批
     * @param all        结果行
     * @param operatorId 操作人
     */
    public void attachReport(EvalRun run, List<EvalResult> all, Long operatorId) {
        reportBuilder.buildAndAttach(run, all);
        runs.updateState(run, operatorId);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private EvalRunResp failBeforeStart(EvalRun run, Long operatorId, String message) {
        EvalRunStateMachine.moveTo(run, EvalRunStatus.FAILED);
        run.setErrorMessage(clip(message, 1024));
        run.setGatePassed(null);
        runs.updateState(run, operatorId);
        attachReport(run, results.listByRun(run.getId()), operatorId);
        operLogWriter.write("eval", "executeFail", "eval_run", String.valueOf(run.getId()), message);
        return assembler.toRunResp(runs.findById(run.getId()).orElse(run));
    }

    private void runOneCase(EvalRun run, EvalResult stub, EvalCase caseRow, JsonNode config, long timeoutMs) {
        LocalDateTime started = LocalDateTime.now();
        stub.setStartedTime(started);
        EvalTrialRequest request = buildRequest(run, stub, caseRow, timeoutMs);
        try {
            EvalArtifact artifact = trialPort.runCase(request);
            applyArtifact(stub, artifact, caseRow, config, started);
        } catch (BizException ex) {
            markTrialError(stub, clip(ex.getMessage(), 1024));
        } catch (RuntimeException ex) {
            markTrialError(stub, clip("试跑异常：" + ex.getClass().getSimpleName(), 1024));
            log.warn("评测 case 抛错 run={} case={}：{}", run.getId(), stub.getCaseKey(), ex.toString());
        }
        results.updateOutcome(stub);
    }

    /**
     * 基础设施失败：ERROR stays ERROR；不跑 RuleScorer / OpenJudge；openjudge_status=SKIPPED（未评）。
     */
    private void markTrialError(EvalResult stub, String message) {
        stub.setVerdict(EvalVerdict.ERROR);
        stub.setErrorMessage(message);
        stub.setOpenjudgeStatus(OpenJudgeStatus.SKIPPED.name());
        stub.setRulePassed(null);
        stub.setAssertionDetailsJson(null);
        stub.setScoresJson(null);
        stub.setOpenjudgeRawJson(null);
        stub.setFinishedTime(LocalDateTime.now());
        if (stub.getLatencyMs() == null && stub.getStartedTime() != null) {
            stub.setLatencyMs((int) Math.min(
                    java.time.Duration.between(stub.getStartedTime(), stub.getFinishedTime()).toMillis(),
                    Integer.MAX_VALUE));
        }
    }

    /**
     * 落 artifact → RuleScorer →（可选）OpenJudge；verdict 只由规则决定。
     */
    private void applyArtifact(EvalResult stub, EvalArtifact artifact, EvalCase caseRow,
                               JsonNode config, LocalDateTime started) {
        stub.setStartedTime(started);
        stub.setFinishedTime(LocalDateTime.now());
        if (artifact == null) {
            markTrialError(stub, "试跑无返回产物");
            return;
        }
        stub.setSessionId(artifact.getSessionId());
        stub.setTraceId(artifact.getTraceId());
        stub.setLatencyMs(artifact.getLatencyMs());
        stub.setCacheHit(Boolean.TRUE.equals(artifact.getCacheHit()) ? 1 : 0);
        stub.setArtifactSummaryJson(writeArtifactJson(artifact));
        stub.setErrorMessage(clip(artifact.getErrorSummary(), 1024));

        String status = artifact.getStatus() == null ? "" : artifact.getStatus().trim().toUpperCase();
        if (!RunStatus.SUCCESS.name().equals(status)) {
            stub.setVerdict(EvalVerdict.ERROR);
            stub.setRulePassed(null);
            stub.setAssertionDetailsJson(null);
            stub.setScoresJson(null);
            stub.setOpenjudgeRawJson(null);
            stub.setOpenjudgeStatus(OpenJudgeStatus.SKIPPED.name());
            if (stub.getErrorMessage() == null || stub.getErrorMessage().isBlank()) {
                stub.setErrorMessage("试跑未成功：" + (status.isEmpty() ? "UNKNOWN" : status));
            }
            return;
        }

        RuleScoreResult score = RuleScorer.score(artifact, parseAssertions(caseRow), json);
        stub.setRulePassed(score.isRulePassed() ? 1 : 0);
        stub.setAssertionDetailsJson(writeAssertionDetails(score));
        ObjectNode scoresRoot = ruleScoresNode(score);
        // verdict 钉死：仅规则
        stub.setVerdict(score.isRulePassed() ? EvalVerdict.PASS : EvalVerdict.FAIL);

        applyOpenJudge(stub, artifact, caseRow, config, scoresRoot);
        try {
            stub.setScoresJson(json.writeValueAsString(scoresRoot));
        } catch (Exception ex) {
            stub.setScoresJson(null);
        }
    }

    /**
     * 软评分：enabled=false → DISABLED；否则调 Port；合并 scores.openjudge；永不改 verdict。
     */
    private void applyOpenJudge(EvalResult stub, EvalArtifact artifact, EvalCase caseRow,
                                JsonNode config, ObjectNode scoresRoot) {
        JsonNode scoring = parseScoring(caseRow);
        boolean runEnabled = EvalRunConfig.openjudgeEnabled(config);
        if (!EvalRunConfig.caseOpenjudgeEnabled(scoring, runEnabled)) {
            stub.setOpenjudgeStatus(OpenJudgeStatus.DISABLED.name());
            return;
        }

        OpenJudgeRequest request = new OpenJudgeRequest();
        request.setCaseKey(stub.getCaseKey());
        request.setArtifact(artifact);
        request.setReference(extractReference(caseRow));
        request.setExpectTools(extractExpectTools(caseRow));
        request.setGraders(resolveGraders(config, scoring, request.getReference(), request.getExpectTools()));
        request.setBaseUrl(EvalRunConfig.openjudgeBaseUrl(config, properties.getOpenjudge().getBaseUrl()));
        request.setTimeoutMs(EvalRunConfig.openjudgeTimeoutMs(config, properties.getOpenjudge().getTimeoutMs()));

        OpenJudgeScoreResult oj = openJudgePort.score(request);
        if (oj == null) {
            oj = OpenJudgeScoreResult.of(OpenJudgeStatus.ERROR, "OpenJudge 无返回");
        }
        OpenJudgeStatus status = oj.getStatus() == null ? OpenJudgeStatus.ERROR : oj.getStatus();
        stub.setOpenjudgeStatus(status.name());
        stub.setOpenjudgeRawJson(clip(oj.getRawJson(), OpenJudgeScorerAdapter.RAW_MAX_CHARS));

        ObjectNode ojNode = scoresRoot.putObject("openjudge");
        ojNode.put("status", status.name());
        if (oj.getScore() != null) {
            ojNode.put("score", oj.getScore());
        }
        if (oj.getReason() != null) {
            ojNode.put("reason", oj.getReason());
        }
        if (oj.getMetadata() != null) {
            ojNode.set("metadata", oj.getMetadata());
        }
        // required=true：本刀只记标志，不改 verdict / gate（交付钉死）
        if (EvalRunConfig.openjudgeRequired(config)
                && (status == OpenJudgeStatus.ERROR || status == OpenJudgeStatus.SKIPPED)) {
            ojNode.put("required_missed", true);
        }
    }

    private List<String> resolveGraders(JsonNode config, JsonNode scoring, String reference,
                                        List<String> expectTools) {
        List<String> caseGraders = EvalRunConfig.caseOpenjudgeGraders(scoring);
        if (!caseGraders.isEmpty()) {
            return caseGraders;
        }
        Set<String> set = new LinkedHashSet<>(EvalRunConfig.openjudgeDefaultGraders(
                config, properties.getOpenjudge().getDefaultGraders()));
        if (set.isEmpty()) {
            set.add("RelevanceGrader");
        }
        if (reference != null && !reference.isBlank()) {
            set.add("CorrectnessGrader");
        }
        if (expectTools != null && !expectTools.isEmpty()) {
            set.add("ToolSelectionGrader");
        }
        return new ArrayList<>(set);
    }

    private String extractReference(EvalCase caseRow) {
        JsonNode expect = parseExpect(caseRow);
        if (expect == null) {
            return null;
        }
        JsonNode ref = expect.get("reference");
        if (ref != null && ref.isTextual() && !ref.asText().isBlank()) {
            return ref.asText().trim();
        }
        return null;
    }

    private List<String> extractExpectTools(EvalCase caseRow) {
        JsonNode expect = parseExpect(caseRow);
        List<String> list = new ArrayList<>();
        if (expect == null) {
            return list;
        }
        JsonNode tools = expect.get("expect_tools");
        if (tools == null) {
            tools = expect.get("expectTools");
        }
        if (tools != null && tools.isArray()) {
            for (JsonNode one : tools) {
                if (one != null && one.isTextual() && !one.asText().isBlank()) {
                    list.add(one.asText().trim());
                }
            }
        }
        return list;
    }

    private JsonNode parseExpect(EvalCase caseRow) {
        if (caseRow == null || caseRow.getExpectJson() == null || caseRow.getExpectJson().isBlank()) {
            return null;
        }
        try {
            return json.readTree(caseRow.getExpectJson());
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode parseScoring(EvalCase caseRow) {
        if (caseRow == null || caseRow.getScoringJson() == null || caseRow.getScoringJson().isBlank()) {
            return null;
        }
        try {
            return json.readTree(caseRow.getScoringJson());
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode parseAssertions(EvalCase caseRow) {
        if (caseRow == null || caseRow.getAssertionsJson() == null || caseRow.getAssertionsJson().isBlank()) {
            return json.createArrayNode();
        }
        try {
            return json.readTree(caseRow.getAssertionsJson());
        } catch (Exception ex) {
            return json.getNodeFactory().textNode("invalid_assertions_json");
        }
    }

    private String writeAssertionDetails(RuleScoreResult score) {
        try {
            return json.writeValueAsString(score.getDetails() == null ? List.of() : score.getDetails());
        } catch (Exception ex) {
            return "[]";
        }
    }

    private ObjectNode ruleScoresNode(RuleScoreResult score) {
        ObjectNode root = json.createObjectNode();
        ObjectNode rule = root.putObject("rule");
        rule.put("blocking_total", score.getBlockingTotal());
        rule.put("blocking_failed", score.getBlockingFailed());
        rule.put("warning_failed", score.getWarningFailed());
        return root;
    }

    private EvalTrialRequest buildRequest(EvalRun run, EvalResult stub, EvalCase caseRow, long timeoutMs) {
        EvalTrialRequest req = new EvalTrialRequest();
        req.setTenantId(run.getTenantId());
        req.setAgentId(run.getAgentId());
        req.setEvalRunId(run.getId());
        req.setCaseKey(stub.getCaseKey());
        req.setCaseId(stub.getCaseId());
        req.setTimeoutMs(timeoutMs);
        req.setCachePolicy("bypass");
        req.setLocale(EvalSpecs.DEFAULT_LOCALE);
        req.setPlanAction("auto_approve");
        req.setMessage("（空用例）");
        if (caseRow != null && caseRow.getInputJson() != null && !caseRow.getInputJson().isBlank()) {
            JsonNode input = assembler.readNode(caseRow.getInputJson());
            if (input != null && input.isObject()) {
                JsonNode message = input.get("message");
                if (message != null && message.isTextual() && !message.asText().isBlank()) {
                    req.setMessage(message.asText().trim());
                }
                JsonNode locale = input.get("locale");
                if (locale != null && locale.isTextual() && !locale.asText().isBlank()) {
                    req.setLocale(locale.asText().trim());
                }
                JsonNode plan = input.get("plan_action");
                if (plan != null && plan.isTextual() && !plan.asText().isBlank()) {
                    req.setPlanAction(plan.asText().trim());
                }
                JsonNode cache = input.get("cache_policy");
                if (cache != null && cache.isTextual() && !cache.asText().isBlank()) {
                    req.setCachePolicy(cache.asText().trim());
                }
            }
        }
        return req;
    }

    private Map<Long, EvalCase> loadCases(EvalRun run) {
        Map<Long, EvalCase> map = new HashMap<>();
        for (EvalCase one : cases.listByVersion(run.getSuiteId(), run.getSuiteVersion())) {
            if (one.getId() != null) {
                map.put(one.getId(), one);
            }
        }
        return map;
    }

    private EvalRunResp finishExecute(Long runId, Long operatorId) {
        EvalRun run = requireWritable(runId);
        if (run.isTerminal()) {
            // 可能已被 cancel 收尾并写过报告；仍允许幂等再生成
            List<EvalResult> rows = results.listByRun(runId);
            attachReport(run, rows, operatorId);
            return assembler.toRunResp(runs.findById(runId).orElse(run));
        }
        if (run.getStatus() == EvalRunStatus.CANCELING) {
            return assembler.toRunResp(run);
        }
        List<EvalResult> all = results.listByRun(runId);
        boolean anyNull = all.stream().anyMatch(EvalResult::isUnfinished);
        if (anyNull) {
            results.markUnfinished(runId, run.getTenantId(), EvalVerdict.SKIPPED, operatorId);
            all = results.listByRun(runId);
        }
        EvalRunStateMachine.applyExecuteFinish(run, all);
        // gate_passed 已由状态机按规则口径写好；报告不改 gate
        reportBuilder.buildAndAttach(run, all);
        runs.updateState(run, operatorId);
        operLogWriter.write("eval", "execute", "eval_run", String.valueOf(runId),
                "评测执行结束 " + run.getStatus().name()
                        + "（pass=" + run.getPassed() + ", fail=" + run.getFailed()
                        + ", error=" + run.getErrored() + ", skip=" + run.getSkipped()
                        + ", gate=" + run.getGatePassed() + "）");
        log.info("评测跑批执行结束 run={} status={} passed={} failed={} errored={} skipped={} gate={}",
                runId, run.getStatus(), run.getPassed(), run.getFailed(), run.getErrored(),
                run.getSkipped(), run.getGatePassed());
        return assembler.toRunResp(runs.findById(runId).orElse(run));
    }

    private String writeArtifactJson(EvalArtifact artifact) {
        try {
            ObjectNode node = json.createObjectNode();
            node.put("session_id", artifact.getSessionId());
            node.put("trace_id", artifact.getTraceId());
            node.put("status", artifact.getStatus());
            node.put("final_answer", artifact.getFinalAnswer());
            node.put("finish_reason", artifact.getFinishReason());
            node.put("cache_hit", Boolean.TRUE.equals(artifact.getCacheHit()));
            if (artifact.getLatencyMs() != null) {
                node.put("latency_ms", artifact.getLatencyMs());
            }
            if (artifact.getToolCalls() != null) {
                node.set("tool_calls", json.valueToTree(artifact.getToolCalls()));
            }
            if (artifact.getUsage() != null) {
                node.set("usage", json.valueToTree(artifact.getUsage()));
            }
            return json.writeValueAsString(node);
        } catch (Exception ex) {
            return null;
        }
    }

    private EvalRun requireWritable(Long id) {
        EvalRun run = runs.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "评测跑批不存在"));
        if (!run.getTenantId().equals(tenantProvider.currentTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测跑批不存在");
        }
        return run;
    }

    private static String clip(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}

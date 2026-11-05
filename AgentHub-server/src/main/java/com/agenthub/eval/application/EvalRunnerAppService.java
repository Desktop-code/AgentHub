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
import com.agenthub.eval.domain.model.OpenJudgeJudge;
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
import com.agenthub.eval.domain.service.JudgeModelResolver;
import com.agenthub.eval.domain.service.JudgeModelUnavailableException;
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
 * <p><b>软评分开关</b>：只有本 run 快照里有非空 {@code judge_model_instance_id} 才出站（且 enabled=true）；
 * 没有模型一律 DISABLED，不发 HTTP、不调 LLM。
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
    private final JudgeModelResolver judgeModelResolver;

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
     * @param judgeModelResolver 打分模型凭据解析（本机环回透传给 sidecar）
     */
    public EvalRunnerAppService(EvalRunRepository runs, EvalResultRepository results, EvalCaseRepository cases,
                                AgentDefRepository agents, TrialEvalPort trialPort,
                                OpenJudgeScorerPort openJudgePort, ReportBuilder reportBuilder,
                                EvalRunFlight flight, EvalAssembler assembler, EvalTenantProvider tenantProvider,
                                OperatorContext operatorContext, OperLogWriter operLogWriter,
                                EvalProperties properties, ObjectMapper json,
                                JudgeModelResolver judgeModelResolver) {
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
        this.judgeModelResolver = judgeModelResolver;
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
     * 软评分：本 run 有 Judge 模型且开关未显式关 → 解凭据 → 调 Port；否则 DISABLED。
     * <p>开关口径钉死为 {@link EvalRunConfig#judgeEnabled(JsonNode)}：{@code enabled=true}
     * <b>且</b> {@code judge_model_instance_id} 非空才调用。只有开关没有模型（历史行、
     * 套件默认开但建跑未选模型）一律 DISABLED，<b>零</b> Judge 出站。
     * <p>凭据口径：按实例 id 从模型中心解密后<b>环回透传</b>给本机 sidecar；
     * 解不出（实例停用 / 凭证缺失 / 解密失败）→ 本 case {@code ERROR}，
     * <b>不</b>退回 sidecar 环境变量 Key 冒充所选实例。
     * <p>合并 scores.openjudge；永不改 verdict / gate。
     */
    private void applyOpenJudge(EvalResult stub, EvalArtifact artifact, EvalCase caseRow,
                                JsonNode config, ObjectNode scoresRoot) {
        JsonNode scoring = parseScoring(caseRow);
        boolean runEnabled = EvalRunConfig.judgeEnabled(config);
        Long judgeModelInstanceId = EvalRunConfig.judgeModelInstanceId(config);
        if (!EvalRunConfig.caseOpenjudgeEnabled(scoring, runEnabled) || judgeModelInstanceId == null) {
            stub.setOpenjudgeStatus(OpenJudgeStatus.DISABLED.name());
            return;
        }

        // 先解凭据：失败即本 case ERROR（软通道），不再出站、不改 verdict/gate
        OpenJudgeJudge judge;
        try {
            judge = judgeModelResolver.resolve(judgeModelInstanceId);
        } catch (JudgeModelUnavailableException ex) {
            writeJudgeUnavailable(stub, scoresRoot, judgeModelInstanceId, ex.getMessage(), config);
            return;
        } catch (RuntimeException ex) {
            // 兜底：任何实现异常都按「凭据不可用」处理，消息只留异常类型
            writeJudgeUnavailable(stub, scoresRoot, judgeModelInstanceId,
                    "打分模型凭据不可用：" + ex.getClass().getSimpleName(), config);
            return;
        }

        OpenJudgeRequest request = new OpenJudgeRequest();
        request.setCaseKey(stub.getCaseKey());
        request.setArtifact(artifact);
        request.setReference(extractReference(caseRow));
        request.setExpectTools(extractExpectTools(caseRow));
        request.setGraders(resolveGraders(config, scoring, request.getReference(), request.getExpectTools()));
        request.setJudgeModelInstanceId(judgeModelInstanceId);
        // 凭据只进请求体（本机环回），不写日志、不入库
        request.setJudge(judge);
        request.setBaseUrl(EvalRunConfig.openjudgeBaseUrl(config, properties.getOpenjudge().getBaseUrl()));
        request.setTimeoutMs(EvalRunConfig.openjudgeTimeoutMs(config, properties.getOpenjudge().getTimeoutMs()));

        OpenJudgeScoreResult oj = openJudgePort.score(request);
        if (oj == null) {
            oj = OpenJudgeScoreResult.of(OpenJudgeStatus.ERROR, "OpenJudge 无返回");
        }
        OpenJudgeStatus status = oj.getStatus() == null ? OpenJudgeStatus.ERROR : oj.getStatus();
        stub.setOpenjudgeStatus(status.name());
        // 第二道闸：即使 Adapter 漏抹，落库前再按 Key 脱敏
        stub.setOpenjudgeRawJson(clip(OpenJudgeScorerAdapter.redact(oj.getRawJson(), judge.getApiKey()),
                OpenJudgeScorerAdapter.RAW_MAX_CHARS));

        ObjectNode ojNode = scoresRoot.putObject("openjudge");
        ojNode.put("status", status.name());
        // 记下本 case 实际使用的 Judge 实例，便于报告/排障对账（只记 id，不记 Key）
        ojNode.put("judge_model_instance_id", judgeModelInstanceId);
        ojNode.put("judge_model", judge.getModel());
        if (oj.getScore() != null) {
            ojNode.put("score", oj.getScore());
        }
        if (oj.getReason() != null) {
            ojNode.put("reason", OpenJudgeScorerAdapter.redact(oj.getReason(), judge.getApiKey()));
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

    /**
     * 凭据不可用时的收尾：status=ERROR、不出站、不改 verdict / gate。
     * <p>故意不写 rawJson（可能是解密细节），只写可读原因与实例 id。
     *
     * @param stub                结果行
     * @param scoresRoot          scores 根
     * @param judgeModelInstanceId 选中的实例 id
     * @param reason              中文原因（不含密钥）
     * @param config              合并配置（用于 required 标记）
     */
    private void writeJudgeUnavailable(EvalResult stub, ObjectNode scoresRoot, Long judgeModelInstanceId,
                                       String reason, JsonNode config) {
        String safeReason = reason == null || reason.isBlank() ? "打分模型凭据不可用" : reason;
        stub.setOpenjudgeStatus(OpenJudgeStatus.ERROR.name());
        log.warn("打分模型凭据不可用，本 case 跳过评分 case={} judgeModelInstanceId={}：{}",
                stub.getCaseKey(), judgeModelInstanceId, safeReason);
        ObjectNode ojNode = scoresRoot.putObject("openjudge");
        ojNode.put("status", OpenJudgeStatus.ERROR.name());
        ojNode.put("judge_model_instance_id", judgeModelInstanceId);
        ojNode.put("reason", safeReason);
        ojNode.put("error", "JUDGE_CREDENTIAL_UNAVAILABLE");
        if (EvalRunConfig.openjudgeRequired(config)) {
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

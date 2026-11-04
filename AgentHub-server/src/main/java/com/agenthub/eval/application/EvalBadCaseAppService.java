package com.agenthub.eval.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.eval.api.dto.EvalBadCasePageReq;
import com.agenthub.eval.api.dto.EvalBadCaseResp;
import com.agenthub.eval.api.dto.EvalImportResp;
import com.agenthub.eval.api.dto.EvalPromoteFromResultReq;
import com.agenthub.eval.api.dto.EvalPromoteFromTraceReq;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.domain.repository.EvalResultRepository;
import com.agenthub.eval.domain.repository.EvalRunRepository;
import com.agenthub.eval.domain.service.EvalTenantProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 坏案回流用例。所属域：eval 的 application。
 * <p>列表跨 run 查 FAIL（可选 ERROR）；promote 经 {@link CaseStoreAppService#upsertCase} 升版，
 * 不改历史 suite_version 行；不触发 trial / 模型。
 * <p>幂等：同 result 多次 promote <b>允许</b>多条（不同 case_key），避免静默丢回流。
 */
@Slf4j
@Service
public class EvalBadCaseAppService {

    private final EvalResultRepository results;
    private final EvalRunRepository runs;
    private final CaseStoreAppService caseStore;
    private final EvalAssembler assembler;
    private final EvalTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final ObjectMapper json;

    /**
     * @param results         结果仓储
     * @param runs            跑批仓储
     * @param caseStore       升版写 case
     * @param assembler       出参
     * @param tenantProvider  租户
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     * @param json            JSON
     */
    public EvalBadCaseAppService(EvalResultRepository results, EvalRunRepository runs,
                                 CaseStoreAppService caseStore, EvalAssembler assembler,
                                 EvalTenantProvider tenantProvider, OperatorContext operatorContext,
                                 OperLogWriter operLogWriter, ObjectMapper json) {
        this.results = results;
        this.runs = runs;
        this.caseStore = caseStore;
        this.assembler = assembler;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.json = json;
    }

    /**
     * 坏案分页：默认仅 FAIL；includeError 时并入 ERROR。
     *
     * @param req 查询
     * @return 分页
     */
    public PageResult<EvalBadCaseResp> pageBadCases(EvalBadCasePageReq req) {
        long tenantId = tenantProvider.currentTenantId();
        long current = req == null ? 1 : req.getCurrent();
        long size = req == null ? 20 : req.getSize();
        List<EvalVerdict> verdicts = new ArrayList<>();
        verdicts.add(EvalVerdict.FAIL);
        if (req != null && Boolean.TRUE.equals(req.getIncludeError())) {
            verdicts.add(EvalVerdict.ERROR);
        }
        PageResult<EvalResult> page = results.pageByVerdicts(tenantId, verdicts, current, size);
        List<EvalBadCaseResp> rows = page.getRecords().stream().map(this::toBadCase).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    /**
     * 从 result 提升为新 case（目标套件升版）。
     * <p>默认仅 FAIL；ERROR 须 force=true。PASS/SKIPPED 一律拒绝。
     *
     * @param suiteId 目标套件
     * @param req     请求
     * @return 升版结果
     */
    @Transactional
    public EvalImportResp promoteFromResult(Long suiteId, EvalPromoteFromResultReq req) {
        if (req == null || req.getResultId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "resultId 不能为空");
        }
        long tenantId = tenantProvider.currentTenantId();
        EvalResult result = results.findById(req.getResultId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "评测结果不存在"));
        if (!result.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测结果不存在");
        }
        EvalVerdict verdict = result.getVerdict();
        if (verdict == EvalVerdict.FAIL) {
            // ok
        } else if (verdict == EvalVerdict.ERROR && Boolean.TRUE.equals(req.getForce())) {
            // 允许强制回流基础设施失败案
        } else if (verdict == EvalVerdict.ERROR) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ERROR 结果需 force=true 才能回流");
        } else {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅规则失败（FAIL）可回流，当前判定："
                    + (verdict == null ? "未判定" : verdict.name()));
        }

        EvalRun run = runs.findById(result.getRunId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "评测跑批不存在"));
        if (!run.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测跑批不存在");
        }

        EvalCaseSpec spec = buildCaseFromResult(result, run, req);
        EvalImportResp resp = caseStore.upsertCase(suiteId, spec);
        operLogWriter.write("eval", "promoteBadCase", "eval_result", String.valueOf(result.getId()),
                "回流坏案至套件 " + suiteId + " case=" + spec.getCaseKey());
        log.info("坏案回流 result={} → suite={} case={} v{}", result.getId(), suiteId,
                spec.getCaseKey(), resp.getCurrentVersion());
        return resp;
    }

    /**
     * 从观测摘要（前端传入）提升为 case；不读 obs 表。
     *
     * @param suiteId 目标套件
     * @param req     摘要
     * @return 升版结果
     */
    @Transactional
    public EvalImportResp promoteFromTrace(Long suiteId, EvalPromoteFromTraceReq req) {
        if (req == null || req.getMessage() == null || req.getMessage().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "message 不能为空");
        }
        operatorContext.requireUserId();
        EvalCaseSpec spec = new EvalCaseSpec();
        String caseKey = blankToNull(req.getCaseKey());
        if (caseKey == null) {
            String suffix = req.getTraceId() == null || req.getTraceId().isBlank()
                    ? String.valueOf(System.currentTimeMillis() % 100_000)
                    : req.getTraceId().replaceAll("[^A-Za-z0-9._-]", "").substring(0,
                    Math.min(12, req.getTraceId().replaceAll("[^A-Za-z0-9._-]", "").length()));
            caseKey = "bc-trace-" + (suffix.isBlank() ? "x" : suffix);
        }
        spec.setCaseKey(caseKey);
        spec.setTitle(blankToNull(req.getTitle()) != null ? req.getTitle().trim()
                : "观测回流：" + clip(req.getMessage().trim(), 40));
        spec.setEnabled(1);
        spec.setSortOrder(0);
        ObjectNode input = json.createObjectNode();
        input.put("message", req.getMessage().trim());
        spec.setInput(input);

        ObjectNode expect = json.createObjectNode();
        if (req.getFinalAnswer() != null && !req.getFinalAnswer().isBlank()) {
            expect.put("reference", clip(req.getFinalAnswer().trim(), 4000));
        }
        if (req.getNote() != null && !req.getNote().isBlank()) {
            expect.put("note", clip(req.getNote().trim(), 1000));
        }
        if (req.getSessionId() != null) {
            expect.put("from_session_id", req.getSessionId());
        }
        if (req.getTraceId() != null) {
            expect.put("from_trace_id", req.getTraceId());
        }
        if (!expect.isEmpty()) {
            spec.setExpect(expect);
        }
        List<String> tagList = new ArrayList<>();
        tagList.add("bad-case");
        tagList.add("from-trace");
        spec.setTags(tagList);

        EvalImportResp resp = caseStore.upsertCase(suiteId, spec);
        operLogWriter.write("eval", "promoteFromTrace", "eval_suite", String.valueOf(suiteId),
                "观测回流 case=" + spec.getCaseKey());
        return resp;
    }

    private EvalCaseSpec buildCaseFromResult(EvalResult result, EvalRun run, EvalPromoteFromResultReq req) {
        EvalCaseSpec spec = new EvalCaseSpec();
        String caseKey = blankToNull(req.getCaseKey());
        if (caseKey == null) {
            String base = result.getCaseKey() == null ? "case" : result.getCaseKey();
            caseKey = "bc-" + base + "-" + result.getId();
        }
        spec.setCaseKey(caseKey);
        String title = blankToNull(req.getTitle());
        if (title == null) {
            title = "坏案回流：" + (result.getCaseKey() == null ? caseKey : result.getCaseKey());
        }
        spec.setTitle(title);
        spec.setEnabled(1);
        spec.setSortOrder(0);

        ObjectNode input = json.createObjectNode();
        input.put("message", "（坏案回流，请按需改写输入）");
        // 若 artifact 有 final_answer，放进 expect.reference 便于对照
        ObjectNode expect = json.createObjectNode();
        var artifact = assembler.readNode(result.getArtifactSummaryJson());
        if (artifact != null && artifact.has("final_answer") && artifact.get("final_answer").isTextual()) {
            expect.put("reference", clip(artifact.get("final_answer").asText(), 4000));
        }
        if (req.getNote() != null && !req.getNote().isBlank()) {
            expect.put("note", clip(req.getNote().trim(), 1000));
        }
        expect.put("from_run_id", run.getId());
        expect.put("from_result_id", result.getId());
        expect.put("from_verdict", result.getVerdict() == null ? "" : result.getVerdict().name());
        spec.setInput(input);
        spec.setExpect(expect);

        List<String> tags = new ArrayList<>();
        tags.add("bad-case");
        tags.add("from-run-" + run.getId());
        if (result.getVerdict() != null) {
            tags.add("verdict-" + result.getVerdict().name().toLowerCase());
        }
        spec.setTags(tags);

        // 把失败断言摘要塞进 scoring 备注，便于套件详情只读查看
        ObjectNode scoring = json.createObjectNode();
        ObjectNode meta = scoring.putObject("promote");
        meta.put("source", "from-result");
        meta.put("result_id", result.getId());
        if (result.getAssertionDetailsJson() != null) {
            meta.set("assertion_details", assembler.readNode(result.getAssertionDetailsJson()));
        }
        spec.setScoring(scoring);
        return spec;
    }

    private EvalBadCaseResp toBadCase(EvalResult one) {
        EvalBadCaseResp resp = new EvalBadCaseResp();
        resp.setResultId(one.getId());
        resp.setRunId(one.getRunId());
        resp.setCaseKey(one.getCaseKey());
        resp.setVerdict(one.getVerdict() == null ? null : one.getVerdict().name());
        resp.setRulePassed(one.getRulePassed());
        resp.setOpenjudgeStatus(one.getOpenjudgeStatus());
        resp.setScores(assembler.readNode(one.getScoresJson()));
        resp.setAssertionDetails(assembler.readNode(one.getAssertionDetailsJson()));
        resp.setArtifactSummary(assembler.readNode(one.getArtifactSummaryJson()));
        resp.setSessionId(one.getSessionId());
        resp.setTraceId(one.getTraceId());
        resp.setLatencyMs(one.getLatencyMs());
        resp.setErrorMessage(one.getErrorMessage());
        resp.setFinishedTime(one.getFinishedTime());
        resp.setCreateTime(one.getCreateTime());
        if (one.getRunId() != null) {
            runs.findById(one.getRunId()).ifPresent(run -> {
                resp.setSuiteId(run.getSuiteId());
                resp.setSuiteVersion(run.getSuiteVersion());
                resp.setAgentId(run.getAgentId());
            });
        }
        return resp;
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static String clip(String raw, int max) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= max ? raw : raw.substring(0, max);
    }
}

package com.agenthub.eval.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.text.TextClip;
import com.agenthub.eval.api.dto.EvalScopeExportReq;
import com.agenthub.eval.api.dto.EvalScopeExportResp;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalScopeBridgeResult;
import com.agenthub.eval.domain.model.EvalScopeExportRequest;
import com.agenthub.eval.domain.model.EvalScopeStatus;
import com.agenthub.eval.domain.repository.EvalResultRepository;
import com.agenthub.eval.domain.repository.EvalRunRepository;
import com.agenthub.eval.domain.service.EvalRunConfig;
import com.agenthub.eval.domain.service.EvalScopeBridgePort;
import com.agenthub.eval.domain.service.EvalTenantProvider;
import com.agenthub.eval.infra.EvalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * EvalScope 可选桥应用服务。所属域：eval 的 application。
 * <p>仅响应显式 API；<b>不</b>挂 Runner / ReportBuilder 主路径 hook。
 * enabled=false → {@link BizException}「EvalScope 桥未启用」，零落盘零外呼。
 * 桥失败 soft，不改 gate_passed / verdict。
 */
@Slf4j
@Service
public class EvalScopeBridgeAppService {

    private final EvalRunRepository runs;
    private final EvalResultRepository results;
    private final EvalScopeBridgePort bridge;
    private final EvalTenantProvider tenantProvider;
    private final EvalProperties properties;
    private final EvalAssembler assembler;
    private final ObjectMapper json;

    /**
     * @param runs           跑批
     * @param results        结果
     * @param bridge         桥 Port
     * @param tenantProvider 租户
     * @param properties     全局配置
     * @param assembler      JSON 节点读取
     * @param json           ObjectMapper
     */
    public EvalScopeBridgeAppService(EvalRunRepository runs, EvalResultRepository results,
                                     EvalScopeBridgePort bridge, EvalTenantProvider tenantProvider,
                                     EvalProperties properties, EvalAssembler assembler, ObjectMapper json) {
        this.runs = runs;
        this.results = results;
        this.bridge = bridge;
        this.tenantProvider = tenantProvider;
        this.properties = properties;
        this.assembler = assembler;
        this.json = json;
    }

    /**
     * 导出已完成 run 的 EvalScope 清单；可选 HTTP 触发外部任务。
     *
     * @param runId 跑批
     * @param req   可选 benchmark / trigger / note
     * @return 出参（status/path/jobId）
     */
    public EvalScopeExportResp export(Long runId, EvalScopeExportReq req) {
        EvalRun run = requireReadableRun(runId);
        JsonNode config = readConfig(run.getConfigJson());
        boolean enabled = EvalRunConfig.evalscopeEnabled(config, properties.getEvalscope().isEnabled());
        // 未启用：明确业务错，禁止落盘/外呼
        if (!enabled) {
            throw new BizException(ErrorCode.BAD_REQUEST, "EvalScope 桥未启用");
        }

        List<EvalResult> rows = results.listByRun(run.getId());
        if (rows == null || rows.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "跑批无结果，拒绝导出空清单");
        }

        String exportRoot = blankTo(
                properties.getEvalscope().getExportDir(),
                properties.getReport().getWorkspaceDir(),
                "./workspace-test");
        Path exportDir = Path.of(exportRoot).resolve("eval-" + run.getId()).resolve("evalscope");

        EvalScopeExportRequest bridgeReq = new EvalScopeExportRequest();
        bridgeReq.setRunId(run.getId());
        bridgeReq.setTenantId(run.getTenantId());
        bridgeReq.setExportDir(exportDir.toString());
        bridgeReq.setManifest(buildManifest(run, rows, req));
        bridgeReq.setBenchmark(req == null ? null : req.getBenchmark());
        bridgeReq.setNote(resolveNote(config, req));
        bridgeReq.setClientToken("run-" + run.getId());
        bridgeReq.setBaseUrl(EvalRunConfig.evalscopeBaseUrl(config, properties.getEvalscope().getBaseUrl()));
        bridgeReq.setTimeoutMs(EvalRunConfig.evalscopeTimeoutMs(config, properties.getEvalscope().getTimeoutMs()));

        // 1) 落盘导出（固定路径覆盖）
        EvalScopeBridgeResult exported = bridge.export(bridgeReq);
        EvalScopeExportResp resp = toResp(exported);
        if (exported.getStatus() != EvalScopeStatus.OK) {
            return resp;
        }

        // 2) 可选触发：仅显式 trigger=true
        boolean wantTrigger = req != null && Boolean.TRUE.equals(req.getTrigger());
        if (!wantTrigger) {
            return resp;
        }
        bridgeReq.setTrigger(true);
        EvalScopeBridgeResult triggered = bridge.trigger(bridgeReq);
        resp.setTriggerStatus(triggered.getStatus() == null ? null : triggered.getStatus().name());
        resp.setJobId(triggered.getJobId());
        if (triggered.getMessage() != null) {
            resp.setMessage(resp.getMessage() + "; trigger=" + triggered.getMessage());
        }
        // 导出已成功：trigger 失败不抹掉 exportPath；整体 status 仍以导出 OK 为主，另记 triggerStatus
        return resp;
    }

    private ObjectNode buildManifest(EvalRun run, List<EvalResult> rows, EvalScopeExportReq req) {
        ObjectNode root = json.createObjectNode();
        root.put("schema", "agenthub.evalscope.export.v1");
        root.put("run_id", run.getId());
        root.put("suite_id", run.getSuiteId() == null ? 0L : run.getSuiteId());
        root.put("suite_version", run.getSuiteVersion() == null ? 0 : run.getSuiteVersion());
        root.put("agent_id", run.getAgentId() == null ? 0L : run.getAgentId());
        root.put("status", run.getStatus() == null ? "" : run.getStatus().name());
        // gate 只读透传，桥不解释、不改写
        if (run.getGatePassed() != null) {
            root.put("gate_passed", run.getGatePassed());
        } else {
            root.putNull("gate_passed");
        }
        if (req != null && req.getBenchmark() != null && !req.getBenchmark().isBlank()) {
            root.put("benchmark", req.getBenchmark().trim());
        }
        String note = resolveNote(readConfig(run.getConfigJson()), req);
        if (note != null && !note.isBlank()) {
            root.put("note", note);
        }
        ArrayNode cases = root.putArray("cases");
        for (EvalResult one : rows) {
            if (one == null) {
                continue;
            }
            ObjectNode row = cases.addObject();
            row.put("case_key", one.getCaseKey() == null ? "" : one.getCaseKey());
            row.put("verdict", one.getVerdict() == null ? "" : one.getVerdict().name());
            if (one.getRulePassed() != null) {
                row.put("rule_passed", one.getRulePassed());
            }
            row.put("openjudge_status", one.getOpenjudgeStatus() == null ? "" : one.getOpenjudgeStatus());
            if (one.getLatencyMs() != null) {
                row.put("latency_ms", one.getLatencyMs());
            }
            if (one.getSessionId() != null) {
                row.put("session_id", one.getSessionId());
            }
            if (one.getTraceId() != null) {
                row.put("trace_id", one.getTraceId());
            }
            // 仅导出终答摘要；不拷贝工具实参/密钥类字段
            String finalAnswer = extractFinalAnswer(one.getArtifactSummaryJson());
            if (finalAnswer != null) {
                row.put("final_answer", TextClip.raw(finalAnswer, 4000));
            }
            if (one.getErrorMessage() != null && !one.getErrorMessage().isBlank()) {
                row.put("error_message", TextClip.raw(maskSecrets(one.getErrorMessage()), 512));
            }
        }
        return root;
    }

    private String extractFinalAnswer(String artifactJson) {
        JsonNode node = assembler.readNode(artifactJson);
        if (node == null || !node.has("final_answer")) {
            return null;
        }
        JsonNode ans = node.get("final_answer");
        return ans != null && ans.isTextual() ? ans.asText() : null;
    }

    private String resolveNote(JsonNode config, EvalScopeExportReq req) {
        if (req != null && req.getNote() != null && !req.getNote().isBlank()) {
            return req.getNote().trim();
        }
        JsonNode es = config != null && config.isObject() ? config.get("evalscope") : null;
        if (es != null && es.hasNonNull("note") && es.get("note").isTextual()) {
            return es.get("note").asText().trim();
        }
        return properties.getEvalscope().getNote();
    }

    private EvalRun requireReadableRun(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "runId 不能为空");
        }
        long tenantId = tenantProvider.currentTenantId();
        EvalRun run = runs.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "评测跑批不存在"));
        if (!run.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测跑批不存在");
        }
        return run;
    }

    private EvalScopeExportResp toResp(EvalScopeBridgeResult result) {
        EvalScopeExportResp resp = new EvalScopeExportResp();
        resp.setStatus(result.getStatus() == null ? EvalScopeStatus.ERROR.name() : result.getStatus().name());
        resp.setExportPath(result.getExportPath());
        resp.setJobId(result.getJobId());
        resp.setMessage(result.getMessage());
        return resp;
    }

    private JsonNode readConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(raw);
            return node == null || node.isNull() ? json.createObjectNode() : node;
        } catch (Exception ex) {
            return json.createObjectNode();
        }
    }

    private static String blankTo(String primary, String secondary, String tertiary) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary.trim();
        }
        return tertiary;
    }


    /**
     * 极简脱敏：掩码常见密钥字段字面量，避免导出物泄漏。
     */
    private static String maskSecrets(String raw) {
        if (raw == null) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!(lower.contains("api_key") || lower.contains("apikey") || lower.contains("password")
                || lower.contains("secret") || lower.contains("token"))) {
            return raw;
        }
        return raw.replaceAll("(?i)(api[_-]?key|password|secret|token)\\s*[:=]\\s*\\S+", "$1=***");
    }
}

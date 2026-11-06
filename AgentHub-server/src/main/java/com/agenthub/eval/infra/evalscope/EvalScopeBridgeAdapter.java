package com.agenthub.eval.infra.evalscope;

import com.agenthub.common.text.TextClip;
import com.agenthub.eval.domain.model.EvalScopeBridgeResult;
import com.agenthub.eval.domain.model.EvalScopeExportRequest;
import com.agenthub.eval.domain.model.EvalScopeStatus;
import com.agenthub.eval.domain.service.EvalScopeBridgePort;
import com.agenthub.eval.infra.evalscope.EvalScopeHttpClient.EvalScopeHttpException;
import com.agenthub.eval.infra.evalscope.EvalScopeHttpClient.HttpJsonResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * EvalScope 薄适配器。所属域：eval 的 infra。
 * <p>选定实现：<b>落盘导出</b>（主）+ <b>可选 HTTP 触发</b>（{@code POST /v1/evalscope/jobs}）。
 * 禁止同进程嵌 Python / 完整 EvalScope AgentLoop。
 * <p>失败 soft → {@link EvalScopeStatus#ERROR}；不改 gate/verdict。
 */
@Slf4j
@Component
public class EvalScopeBridgeAdapter implements EvalScopeBridgePort {

    private final EvalScopeHttpClient client;
    private final ObjectMapper json;

    /**
     * @param client HTTP 客户端
     * @param json   JSON
     */
    public EvalScopeBridgeAdapter(EvalScopeHttpClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    /** {@inheritDoc} */
    @Override
    public EvalScopeBridgeResult export(EvalScopeExportRequest request) {
        if (request == null || request.getExportDir() == null || request.getExportDir().isBlank()) {
            return EvalScopeBridgeResult.of(EvalScopeStatus.ERROR, "导出目录为空");
        }
        if (request.getManifest() == null || request.getManifest().isNull()) {
            return EvalScopeBridgeResult.of(EvalScopeStatus.ERROR, "导出清单为空");
        }
        try {
            Path dir = Path.of(request.getExportDir());
            Files.createDirectories(dir);
            Path file = dir.resolve("manifest.json");
            byte[] bytes = json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(request.getManifest())
                    .getBytes(StandardCharsets.UTF_8);
            // 同路径覆盖：幂等约定
            Files.write(file, bytes);
            String path = file.toString();
            log.info("EvalScope 导出完成 run={} path={}", request.getRunId(), path);
            return EvalScopeBridgeResult.okExport(path, "已导出清单");
        } catch (Exception ex) {
            log.warn("EvalScope 导出失败 run={}：{}", request.getRunId(), ex.toString());
            return EvalScopeBridgeResult.of(EvalScopeStatus.ERROR,
                    TextClip.raw("导出失败：" + ex.getClass().getSimpleName(), 512));
        }
    }

    /** {@inheritDoc} */
    @Override
    public EvalScopeBridgeResult trigger(EvalScopeExportRequest request) {
        if (request == null) {
            return EvalScopeBridgeResult.of(EvalScopeStatus.SKIPPED, "触发请求为空");
        }
        String baseUrl = request.getBaseUrl() == null ? "" : request.getBaseUrl().trim();
        // 未配置 base_url：SKIPPED（不外呼）
        if (baseUrl.isBlank()) {
            return EvalScopeBridgeResult.of(EvalScopeStatus.SKIPPED, "未配置 EvalScope base_url，跳过触发");
        }
        try {
            HttpJsonResponse response = client.createJob(baseUrl, request.getTimeoutMs(), buildTriggerBody(request));
            String jobId = extractJobId(response.getBody());
            if (jobId == null || jobId.isBlank()) {
                return EvalScopeBridgeResult.of(EvalScopeStatus.ERROR, "EvalScope 响应缺少 job_id");
            }
            return EvalScopeBridgeResult.okJob(jobId, "已创建外部任务");
        } catch (EvalScopeHttpException ex) {
            log.info("EvalScope 触发失败 run={}：{}", request.getRunId(), ex.getMessage());
            return EvalScopeBridgeResult.of(EvalScopeStatus.ERROR, TextClip.raw(ex.getMessage(), 512));
        } catch (RuntimeException ex) {
            log.warn("EvalScope 触发未预期异常 run={}：{}", request.getRunId(), ex.toString());
            return EvalScopeBridgeResult.of(EvalScopeStatus.ERROR,
                    TextClip.raw("EvalScope 异常：" + ex.getClass().getSimpleName(), 512));
        }
    }

    private ObjectNode buildTriggerBody(EvalScopeExportRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("client_token", request.getClientToken() == null ? "" : request.getClientToken());
        if (request.getRunId() != null) {
            body.put("run_id", request.getRunId());
        }
        if (request.getBenchmark() != null && !request.getBenchmark().isBlank()) {
            body.put("benchmark", request.getBenchmark().trim());
        }
        if (request.getExportDir() != null) {
            body.put("export_dir", request.getExportDir());
        }
        if (request.getNote() != null && !request.getNote().isBlank()) {
            body.put("note", request.getNote().trim());
        }
        return body;
    }

    private static String extractJobId(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        if (body.hasNonNull("job_id") && body.get("job_id").isTextual()) {
            return body.get("job_id").asText();
        }
        if (body.hasNonNull("id")) {
            JsonNode id = body.get("id");
            return id.isTextual() ? id.asText() : String.valueOf(id.asLong());
        }
        return null;
    }

}

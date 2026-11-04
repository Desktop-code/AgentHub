package com.agenthub.eval.infra.openjudge;

import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.OpenJudgeRequest;
import com.agenthub.eval.domain.model.OpenJudgeScoreResult;
import com.agenthub.eval.domain.model.OpenJudgeStatus;
import com.agenthub.eval.domain.service.OpenJudgeScorerPort;
import com.agenthub.eval.infra.openjudge.OpenJudgeHttpClient.HttpJsonResponse;
import com.agenthub.eval.infra.openjudge.OpenJudgeHttpClient.OpenJudgeHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenJudge HTTP sidecar 适配器。所属域：eval 的 infra。
 * <p>实现 {@link OpenJudgeScorerPort}：健康失败 → SKIPPED；超时/5xx → ERROR；200 → OK。
 * <p>P0 graders 请求字段：RelevanceGrader / CorrectnessGrader / ToolSelectionGrader（由调用方解析列表）。
 * <p>禁止：JVM 内 LLM、同进程 Python、改写 verdict/gate。
 */
@Slf4j
@Component
public class OpenJudgeScorerAdapter implements OpenJudgeScorerPort {

    /** openjudge_raw_json 截断上限（64KB） */
    public static final int RAW_MAX_CHARS = 64 * 1024;

    private final OpenJudgeHttpClient client;
    private final ObjectMapper json;

    /**
     * @param client HTTP 客户端
     * @param json   JSON
     */
    public OpenJudgeScorerAdapter(OpenJudgeHttpClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    /** {@inheritDoc} */
    @Override
    public OpenJudgeScoreResult score(OpenJudgeRequest request) {
        if (request == null) {
            return OpenJudgeScoreResult.of(OpenJudgeStatus.SKIPPED, "OpenJudge 请求为空");
        }
        String baseUrl = request.getBaseUrl() == null || request.getBaseUrl().isBlank()
                ? "http://127.0.0.1:18765" : request.getBaseUrl().trim();
        long timeoutMs = request.getTimeoutMs() <= 0 ? 60_000L : request.getTimeoutMs();

        // 健康检查失败：未部署 / 连接拒绝 → SKIPPED（不重试）
        if (!client.health(baseUrl, Math.min(timeoutMs, 5_000L))) {
            return OpenJudgeScoreResult.of(OpenJudgeStatus.SKIPPED, "OpenJudge sidecar 不可用（健康检查失败）");
        }

        try {
            HttpJsonResponse response = client.evaluate(baseUrl, timeoutMs, buildBody(request));
            return mapOk(response);
        } catch (OpenJudgeHttpException ex) {
            OpenJudgeStatus status = ex.isTimeout() ? OpenJudgeStatus.ERROR : OpenJudgeStatus.ERROR;
            // 5xx / 超时 / 协议错误一律 ERROR；连接类在 health 已拦，evaluate 阶段仍可能掉线
            OpenJudgeScoreResult result = OpenJudgeScoreResult.of(status, clip(ex.getMessage(), 512));
            result.setRawJson(clip(ex.getRawBody(), RAW_MAX_CHARS));
            log.info("OpenJudge 评分失败 case={}：{}", request.getCaseKey(), ex.getMessage());
            return result;
        } catch (RuntimeException ex) {
            OpenJudgeScoreResult result = OpenJudgeScoreResult.of(OpenJudgeStatus.ERROR,
                    clip("OpenJudge 异常：" + ex.getClass().getSimpleName(), 512));
            log.warn("OpenJudge 未预期异常 case={}：{}", request.getCaseKey(), ex.toString());
            return result;
        }
    }

    private ObjectNode buildBody(OpenJudgeRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("case_key", request.getCaseKey() == null ? "" : request.getCaseKey());
        EvalArtifact artifact = request.getArtifact() == null ? new EvalArtifact() : request.getArtifact();
        body.put("final_answer", artifact.getFinalAnswer() == null ? "" : artifact.getFinalAnswer());
        if (request.getReference() != null && !request.getReference().isBlank()) {
            body.put("reference", request.getReference());
        }
        ArrayNode tools = body.putArray("tool_calls");
        if (artifact.getToolCalls() != null) {
            for (EvalArtifact.ToolCallSummary one : artifact.getToolCalls()) {
                if (one == null || one.getName() == null) {
                    continue;
                }
                ObjectNode row = tools.addObject();
                row.put("name", one.getName());
                row.put("success", Boolean.TRUE.equals(one.getSuccess()));
            }
        }
        ArrayNode expectTools = body.putArray("expect_tools");
        if (request.getExpectTools() != null) {
            for (String name : request.getExpectTools()) {
                if (name != null && !name.isBlank()) {
                    expectTools.add(name.trim());
                }
            }
        }
        ArrayNode graders = body.putArray("graders");
        if (request.getGraders() != null) {
            for (String name : request.getGraders()) {
                if (name != null && !name.isBlank()) {
                    graders.add(name.trim());
                }
            }
        }
        return body;
    }

    private OpenJudgeScoreResult mapOk(HttpJsonResponse response) {
        JsonNode body = response.getBody() == null ? json.createObjectNode() : response.getBody();
        OpenJudgeScoreResult result = new OpenJudgeScoreResult();
        result.setStatus(OpenJudgeStatus.OK);
        if (body.has("score") && body.get("score").isNumber()) {
            result.setScore(body.get("score").asDouble());
        }
        if (body.has("reason") && body.get("reason").isTextual()) {
            result.setReason(body.get("reason").asText());
        } else {
            result.setReason("OpenJudge 评分成功");
        }
        if (body.has("metadata")) {
            result.setMetadata(body.get("metadata"));
        } else if (body.has("graders")) {
            ObjectNode meta = json.createObjectNode();
            meta.set("graders", body.get("graders"));
            result.setMetadata(meta);
        }
        result.setRawJson(clip(response.getRaw(), RAW_MAX_CHARS));
        return result;
    }

    private static String clip(String raw, int max) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= max ? raw : raw.substring(0, max);
    }
}

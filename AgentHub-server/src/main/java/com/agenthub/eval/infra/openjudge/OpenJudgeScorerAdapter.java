package com.agenthub.eval.infra.openjudge;

import com.agenthub.common.text.TextClip;
import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.OpenJudgeJudge;
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
 * <p><b>凭据</b>：本 run 选了打分模型时，请求体额外带 {@code model} / {@code base_url} /
 * {@code api_key}（本机环回透传）；无论成功失败，返回的 reason / rawJson 都会把 Key 抹成 {@code ***}。
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
            return mapOk(response, mask(request));
        } catch (OpenJudgeHttpException ex) {
            OpenJudgeStatus status = ex.isTimeout() ? OpenJudgeStatus.ERROR : OpenJudgeStatus.ERROR;
            // 5xx / 超时 / 协议错误一律 ERROR；连接类在 health 已拦，evaluate 阶段仍可能掉线
            // 错误体来自 sidecar 原文，先抹 Key 再落 reason / rawJson（防 sidecar 回显请求体）
            String maskedMessage = redact(ex.getMessage(), mask(request));
            OpenJudgeScoreResult result = OpenJudgeScoreResult.of(status, TextClip.raw(maskedMessage, 512));
            result.setRawJson(TextClip.raw(redact(ex.getRawBody(), mask(request)), RAW_MAX_CHARS));
            log.info("OpenJudge 评分失败 case={}：{}", request.getCaseKey(), maskedMessage);
            return result;
        } catch (RuntimeException ex) {
            OpenJudgeScoreResult result = OpenJudgeScoreResult.of(OpenJudgeStatus.ERROR,
                    TextClip.raw("OpenJudge 异常：" + ex.getClass().getSimpleName(), 512));
            log.warn("OpenJudge 未预期异常 case={}：{}", request.getCaseKey(), ex.getClass().getSimpleName());
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
        // 指定 Judge 模型：只有本 run 显式选了模型才会走到这里；sidecar 按该实例评分
        if (request.getJudgeModelInstanceId() != null) {
            body.put("judge_model_instance_id", request.getJudgeModelInstanceId());
        }
        // 环回透传凭据：字段名与 sidecar EvaluateRequest 对齐（model / base_url / api_key）。
        // 仅本机 sidecar 可见，不落库、不返回前端；缺失时 sidecar 回落自身环境变量。
        OpenJudgeJudge judge = request.getJudge();
        if (judge != null) {
            if (judge.getModel() != null && !judge.getModel().isBlank()) {
                body.put("model", judge.getModel().trim());
            }
            if (judge.getBaseUrl() != null && !judge.getBaseUrl().isBlank()) {
                body.put("base_url", judge.getBaseUrl().trim());
            }
            if (judge.getApiKey() != null && !judge.getApiKey().isBlank()) {
                body.put("api_key", judge.getApiKey());
            }
        }
        return body;
    }

    /**
     * 取出本次请求的明文 Key，用于响应脱敏。
     *
     * @param request 请求
     * @return Key；没有则空串
     */
    private static String mask(OpenJudgeRequest request) {
        OpenJudgeJudge judge = request == null ? null : request.getJudge();
        String key = judge == null ? null : judge.getApiKey();
        return key == null ? "" : key;
    }

    /**
     * 把文本里出现的明文 Key 换成 {@code ***}。
     * <p>sidecar 若把请求体回显进错误体，rawJson 会落库；这里做最后一道闸。
     *
     * @param text 原始文本，可空
     * @param key  明文 Key，空则原样返回
     * @return 脱敏后的文本
     */
    public static String redact(String text, String key) {
        if (text == null || text.isEmpty() || key == null || key.isBlank()) {
            return text;
        }
        return text.replace(key, "***");
    }

    /**
     * 递归脱敏 metadata：sidecar 若把请求体塞进 metadata，Key 也会一并抹掉。
     *
     * @param node   sidecar 返回的 metadata
     * @param apiKey 明文 Key
     * @return 脱敏后的节点；序列化异常时返回省略说明（宁可少字段也不泄 Key）
     */
    private JsonNode redactNode(JsonNode node, String apiKey) {
        if (node == null || apiKey == null || apiKey.isBlank()) {
            return node;
        }
        try {
            String text = json.writeValueAsString(node);
            if (!text.contains(apiKey)) {
                return node;
            }
            return json.readTree(redact(text, apiKey));
        } catch (Exception ex) {
            ObjectNode fallback = json.createObjectNode();
            fallback.put("note", "metadata 已省略（含敏感字段）");
            return fallback;
        }
    }

    private OpenJudgeScoreResult mapOk(HttpJsonResponse response, String apiKey) {
        JsonNode body = response.getBody() == null ? json.createObjectNode() : response.getBody();
        OpenJudgeScoreResult result = new OpenJudgeScoreResult();
        result.setStatus(OpenJudgeStatus.OK);
        if (body.has("score") && body.get("score").isNumber()) {
            result.setScore(body.get("score").asDouble());
        }
        if (body.has("reason") && body.get("reason").isTextual()) {
            result.setReason(TextClip.raw(redact(body.get("reason").asText(), apiKey), 512));
        } else {
            result.setReason("OpenJudge 评分成功");
        }
        if (body.has("metadata")) {
            result.setMetadata(redactNode(body.get("metadata"), apiKey));
        } else if (body.has("graders")) {
            ObjectNode meta = json.createObjectNode();
            meta.set("graders", body.get("graders"));
            result.setMetadata(meta);
        }
        result.setRawJson(TextClip.raw(redact(response.getRaw(), apiKey), RAW_MAX_CHARS));
        return result;
    }

}

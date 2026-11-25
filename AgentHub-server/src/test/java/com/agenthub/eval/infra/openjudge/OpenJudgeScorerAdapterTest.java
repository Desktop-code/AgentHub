package com.agenthub.eval.infra.openjudge;

import com.agenthub.eval.domain.model.EvalArtifact;
import com.agenthub.eval.domain.model.OpenJudgeJudge;
import com.agenthub.eval.domain.model.OpenJudgeRequest;
import com.agenthub.eval.domain.model.OpenJudgeScoreResult;
import com.agenthub.eval.domain.model.OpenJudgeStatus;
import com.agenthub.eval.infra.openjudge.OpenJudgeHttpClient.HttpJsonResponse;
import com.agenthub.eval.infra.openjudge.OpenJudgeHttpClient.OpenJudgeHttpException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenJudgeScorerAdapter 单测。所属域：eval。
 * <p>全程 mock HTTP 客户端；禁止真 sidecar / 真 Judge 模型。
 */
class OpenJudgeScorerAdapterTest {

    private final ObjectMapper json = new ObjectMapper();
    private OpenJudgeHttpClient client;
    private OpenJudgeScorerAdapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(OpenJudgeHttpClient.class);
        adapter = new OpenJudgeScorerAdapter(client, json);
    }

    @Test
    void healthFailReturnsSkippedWithoutEvaluate() {
        when(client.health(anyString(), anyLong())).thenReturn(false);

        OpenJudgeScoreResult result = adapter.score(sampleRequest());

        assertEquals(OpenJudgeStatus.SKIPPED, result.getStatus());
        verify(client, never()).evaluate(anyString(), anyLong(), any());
    }

    @Test
    void evaluate200MapsOkWithScore() {
        when(client.health(anyString(), anyLong())).thenReturn(true);
        ObjectNode body = json.createObjectNode();
        body.put("score", 4.0);
        body.put("reason", "ok");
        when(client.evaluate(anyString(), anyLong(), any()))
                .thenReturn(new HttpJsonResponse(200, body, body.toString()));

        OpenJudgeScoreResult result = adapter.score(sampleRequest());

        assertEquals(OpenJudgeStatus.OK, result.getStatus());
        assertEquals(4.0, result.getScore(), 0.01);
        assertNotNull(result.getRawJson());
    }

    @Test
    void timeoutMapsError() {
        when(client.health(anyString(), anyLong())).thenReturn(true);
        when(client.evaluate(anyString(), anyLong(), any()))
                .thenThrow(new OpenJudgeHttpException("sidecar 超时", 0, null, null));

        OpenJudgeScoreResult result = adapter.score(sampleRequest());

        assertEquals(OpenJudgeStatus.ERROR, result.getStatus());
        assertTrue(result.getReason().contains("超时"));
        assertNull(result.getScore());
    }

    @Test
    void http5xxMapsError() {
        when(client.health(anyString(), anyLong())).thenReturn(true);
        when(client.evaluate(anyString(), anyLong(), any()))
                .thenThrow(new OpenJudgeHttpException("sidecar HTTP 503", 503, "down", null));

        OpenJudgeScoreResult result = adapter.score(sampleRequest());

        assertEquals(OpenJudgeStatus.ERROR, result.getStatus());
        assertEquals("down", result.getRawJson());
    }

    /**
     * 选了打分模型时，请求体必须带 judge_model_instance_id（sidecar 据此评分）；
     * 未选则不出现该字段（杜绝默认模型）。
     */
    @Test
    void evaluateBodyCarriesJudgeModelInstanceIdOnlyWhenSelected() {
        when(client.health(anyString(), anyLong())).thenReturn(true);
        ObjectNode body = json.createObjectNode();
        body.put("score", 4.0);
        when(client.evaluate(anyString(), anyLong(), any()))
                .thenReturn(new HttpJsonResponse(200, body, body.toString()));

        OpenJudgeRequest withJudge = sampleRequest();
        withJudge.setJudgeModelInstanceId(501L);
        adapter.score(withJudge);

        OpenJudgeRequest withoutJudge = sampleRequest();
        adapter.score(withoutJudge);

        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> captor =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(client, org.mockito.Mockito.times(2)).evaluate(anyString(), anyLong(), captor.capture());
        List<com.fasterxml.jackson.databind.JsonNode> bodies = captor.getAllValues();
        assertEquals(501L, bodies.get(0).get("judge_model_instance_id").asLong());
        assertNull(bodies.get(1).get("judge_model_instance_id"));
    }

    /**
     * 环回透传凭据：请求体字段名钉死为 model / base_url / api_key，
     * 与 scripts/openjudge-sidecar 的 EvaluateRequest 对齐。
     */
    @Test
    void evaluateBodyCarriesJudgeCredentialFields() {
        when(client.health(anyString(), anyLong())).thenReturn(true);
        ObjectNode body = json.createObjectNode();
        body.put("score", 4.0);
        when(client.evaluate(anyString(), anyLong(), any()))
                .thenReturn(new HttpJsonResponse(200, body, body.toString()));

        OpenJudgeRequest req = sampleRequest();
        req.setJudgeModelInstanceId(501L);
        req.setJudge(new OpenJudgeJudge("qwen-plus", "https://api.example.com/v1", KEY));
        adapter.score(req);

        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> captor =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(client).evaluate(anyString(), anyLong(), captor.capture());
        com.fasterxml.jackson.databind.JsonNode sent = captor.getValue();
        assertEquals("qwen-plus", sent.get("model").asText());
        assertEquals("https://api.example.com/v1", sent.get("base_url").asText());
        assertEquals(KEY, sent.get("api_key").asText());
    }

    /**
     * 未选模型 / 无凭据：body 里不得出现 model / base_url / api_key，
     * 由 sidecar 回落自身环境变量（历史行为）。
     */
    @Test
    void evaluateBodyOmitsCredentialFieldsWhenJudgeAbsent() {
        when(client.health(anyString(), anyLong())).thenReturn(true);
        ObjectNode body = json.createObjectNode();
        body.put("score", 4.0);
        when(client.evaluate(anyString(), anyLong(), any()))
                .thenReturn(new HttpJsonResponse(200, body, body.toString()));

        adapter.score(sampleRequest());

        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> captor =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(client).evaluate(anyString(), anyLong(), captor.capture());
        assertNull(captor.getValue().get("model"));
        assertNull(captor.getValue().get("base_url"));
        assertNull(captor.getValue().get("api_key"));
    }

    /**
     * sidecar 若把请求体回显进错误体：reason / rawJson 必须抹成 ***，不得落明文 Key。
     */
    @Test
    void errorBodyEchoingKeyIsRedacted() {
        when(client.health(anyString(), anyLong())).thenReturn(true);
        String leaked = "{\"detail\":\"bad key " + KEY + "\"}";
        when(client.evaluate(anyString(), anyLong(), any()))
                .thenThrow(new OpenJudgeHttpException("sidecar HTTP 400 " + KEY, 400, leaked, null));

        OpenJudgeRequest req = sampleRequest();
        req.setJudgeModelInstanceId(501L);
        req.setJudge(new OpenJudgeJudge("qwen-plus", null, KEY));
        OpenJudgeScoreResult result = adapter.score(req);

        assertEquals(OpenJudgeStatus.ERROR, result.getStatus());
        assertFalse(result.getReason().contains(KEY), "reason 不得含 Key");
        assertFalse(result.getRawJson().contains(KEY), "rawJson 不得含 Key");
        assertTrue(result.getRawJson().contains("***"));
    }

    /**
     * 成功响应里若出现 Key（异常回显/被代理插入）：reason 与 rawJson 同样脱敏。
     */
    @Test
    void okBodyEchoingKeyIsRedacted() {
        when(client.health(anyString(), anyLong())).thenReturn(true);
        String leaked = "{\"score\":4.0,\"reason\":\"used " + KEY + "\"}";
        ObjectNode body = json.createObjectNode().put("score", 4.0).put("reason", "used " + KEY);
        body.set("metadata", json.createObjectNode().put("echo_api_key", KEY));
        when(client.evaluate(anyString(), anyLong(), any()))
                .thenReturn(new HttpJsonResponse(200, body, leaked));

        OpenJudgeRequest req = sampleRequest();
        req.setJudge(new OpenJudgeJudge("qwen-plus", null, KEY));
        OpenJudgeScoreResult result = adapter.score(req);

        assertEquals(OpenJudgeStatus.OK, result.getStatus());
        assertFalse(result.getReason().contains(KEY));
        assertFalse(result.getRawJson().contains(KEY));
        assertFalse(String.valueOf(result.getMetadata()).contains(KEY), "metadata 也不得含 Key");
    }

    private static final String KEY = "sk-adapter-test-key-1234";

    private OpenJudgeRequest sampleRequest() {
        OpenJudgeRequest req = new OpenJudgeRequest();
        req.setCaseKey("c0");
        req.setBaseUrl("http://127.0.0.1:18765");
        req.setTimeoutMs(5_000L);
        req.setGraders(List.of("RelevanceGrader"));
        EvalArtifact art = new EvalArtifact();
        art.setFinalAnswer("答案");
        req.setArtifact(art);
        return req;
    }
}

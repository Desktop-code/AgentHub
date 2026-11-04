package com.agenthub.eval.infra.openjudge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenJudge sidecar 低层 HTTP 客户端。所属域：eval 的 infra。
 * <p>契约（钉死，单测按此 mock）：
 * <ul>
 *   <li>{@code GET {base}/health} → 2xx 视为健康；连接拒绝/非 2xx → 不健康；</li>
 *   <li>{@code POST {base}/v1/aevaluate} + JSON body → 2xx 且可解析 score；超时/5xx → 失败。</li>
 * </ul>
 * <p><b>无重试</b>（写死）：健康失败直接 SKIPPED，评分失败直接 ERROR，避免对宕机 sidecar 轰炸。
 * 线程：无状态，可并发；每次请求独立 timeout。
 */
public class OpenJudgeHttpClient {

    private final HttpClient httpClient;
    private final ObjectMapper json;

    /**
     * @param httpClient JDK HttpClient
     * @param json       JSON
     */
    public OpenJudgeHttpClient(HttpClient httpClient, ObjectMapper json) {
        this.httpClient = httpClient;
        this.json = json;
    }

    /**
     * 探活。连接失败 / 非 2xx → false；不抛异常。
     *
     * @param baseUrl   sidecar 基址
     * @param timeoutMs 超时
     * @return true 表示健康
     */
    public boolean health(String baseUrl, long timeoutMs) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/health"))
                    .timeout(Duration.ofMillis(Math.max(1_000L, timeoutMs)))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 调用 aevaluate。
     *
     * @param baseUrl   基址
     * @param timeoutMs 超时
     * @param body      请求体
     * @return HTTP 结果
     * @throws OpenJudgeHttpException 超时 / 非 2xx / IO
     */
    public HttpJsonResponse evaluate(String baseUrl, long timeoutMs, JsonNode body) {
        long timeout = Math.max(1_000L, timeoutMs);
        String payload;
        try {
            payload = json.writeValueAsString(body == null ? json.createObjectNode() : body);
        } catch (Exception ex) {
            throw new OpenJudgeHttpException("请求体序列化失败", 0, null, ex);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/v1/aevaluate"))
                    .timeout(Duration.ofMillis(timeout))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            String raw = response.body();
            if (code < 200 || code >= 300) {
                throw new OpenJudgeHttpException("sidecar HTTP " + code, code, raw, null);
            }
            JsonNode node;
            try {
                node = json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            } catch (Exception ex) {
                throw new OpenJudgeHttpException("sidecar 响应不是 JSON", code, raw, ex);
            }
            return new HttpJsonResponse(code, node, raw);
        } catch (OpenJudgeHttpException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenJudgeHttpException("sidecar 调用被中断", 0, null, ex);
        } catch (IOException ex) {
            // 含 HttpTimeoutException
            String message = ex.getClass().getSimpleName();
            if (message.contains("Timeout") || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timed out"))) {
                throw new OpenJudgeHttpException("sidecar 超时", 0, null, ex);
            }
            throw new OpenJudgeHttpException("sidecar 连接失败：" + message, 0, null, ex);
        }
    }

    private static String trimSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://127.0.0.1:18765";
        }
        String raw = baseUrl.trim();
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    /**
     * HTTP 成功响应。所属域：eval 的 infra。
     */
    public static final class HttpJsonResponse {
        private final int statusCode;
        private final JsonNode body;
        private final String raw;

        /**
         * @param statusCode HTTP 状态
         * @param body       解析后的 JSON
         * @param raw        原文
         */
        public HttpJsonResponse(int statusCode, JsonNode body, String raw) {
            this.statusCode = statusCode;
            this.body = body;
            this.raw = raw;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public JsonNode getBody() {
            return body;
        }

        public String getRaw() {
            return raw;
        }
    }

    /**
     * sidecar 调用失败。所属域：eval 的 infra。不进全局异常映射，由 Adapter 转 status。
     */
    public static class OpenJudgeHttpException extends RuntimeException {
        private final int statusCode;
        private final String rawBody;

        /**
         * @param message    说明
         * @param statusCode HTTP 码，无则 0
         * @param rawBody    原文，可空
         * @param cause      原因
         */
        public OpenJudgeHttpException(String message, int statusCode, String rawBody, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
            this.rawBody = rawBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getRawBody() {
            return rawBody;
        }

        /**
         * @return 是否超时类失败
         */
        public boolean isTimeout() {
            String msg = getMessage() == null ? "" : getMessage();
            return msg.contains("超时");
        }
    }
}

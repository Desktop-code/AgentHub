package com.agenthub.eval.infra.evalscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * EvalScope sidecar 低层 HTTP 客户端。所属域：eval 的 infra。
 * <p>契约（钉死，单测按此 mock）：
 * <ul>
 *   <li>{@code POST {base}/v1/evalscope/jobs} + JSON → 2xx 且可解析 {@code job_id}/{@code id}；</li>
 *   <li>超时 / 非 2xx / IO → {@link EvalScopeHttpException}。</li>
 * </ul>
 * <p><b>无重试</b>（写死）：失败直接 ERROR，避免对宕机 sidecar 轰炸。线程：无状态。
 */
public class EvalScopeHttpClient {

    private final HttpClient httpClient;
    private final ObjectMapper json;

    /**
     * @param httpClient JDK HttpClient
     * @param json       JSON
     */
    public EvalScopeHttpClient(HttpClient httpClient, ObjectMapper json) {
        this.httpClient = httpClient;
        this.json = json;
    }

    /**
     * 创建外部任务。
     *
     * @param baseUrl   基址
     * @param timeoutMs 超时
     * @param body      请求体
     * @return HTTP 结果
     * @throws EvalScopeHttpException 超时 / 非 2xx / IO
     */
    public HttpJsonResponse createJob(String baseUrl, long timeoutMs, JsonNode body) {
        long timeout = Math.max(1_000L, timeoutMs);
        String payload;
        try {
            payload = json.writeValueAsString(body == null ? json.createObjectNode() : body);
        } catch (Exception ex) {
            throw new EvalScopeHttpException("请求体序列化失败", 0, null, ex);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/v1/evalscope/jobs"))
                    .timeout(Duration.ofMillis(timeout))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            String raw = response.body();
            if (code < 200 || code >= 300) {
                throw new EvalScopeHttpException("EvalScope HTTP " + code, code, raw, null);
            }
            JsonNode node;
            try {
                node = json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            } catch (Exception ex) {
                throw new EvalScopeHttpException("EvalScope 响应不是 JSON", code, raw, ex);
            }
            return new HttpJsonResponse(code, node, raw);
        } catch (EvalScopeHttpException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EvalScopeHttpException("EvalScope 调用被中断", 0, null, ex);
        } catch (IOException ex) {
            String message = ex.getClass().getSimpleName();
            if (message.contains("Timeout")
                    || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timed out"))) {
                throw new EvalScopeHttpException("EvalScope 超时", 0, null, ex);
            }
            throw new EvalScopeHttpException("EvalScope 连接失败：" + message, 0, null, ex);
        }
    }

    private static String trimSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://127.0.0.1:19876";
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
     * sidecar 调用失败。所属域：eval 的 infra。
     */
    public static class EvalScopeHttpException extends RuntimeException {
        private final int statusCode;
        private final String rawBody;

        /**
         * @param message    说明
         * @param statusCode HTTP 码，无则 0
         * @param rawBody    原文
         * @param cause      原因
         */
        public EvalScopeHttpException(String message, int statusCode, String rawBody, Throwable cause) {
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
         * @return 是否超时
         */
        public boolean isTimeout() {
            String msg = getMessage() == null ? "" : getMessage();
            return msg.contains("超时");
        }
    }
}

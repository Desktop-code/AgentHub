package com.agenthub.tool.infra.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP Streamable HTTP 客户端。所属域：tool 的 infra。
 * <p>
 * 按协议 2025-03-26（替代旧 HTTP+SSE）：向单一 MCP 端点 {@code baseUrl} 逐条 POST JSON-RPC；
 * {@code Accept: application/json, text/event-stream}；服务端可回 JSON 或该请求作用域内的 SSE 流。
 * initialize 响应若带 {@code Mcp-Session-Id}，后续请求必须原样带回；可选 {@code MCP-Protocol-Version}。
 * 同步：initialize → notifications/initialized → tools/list（可翻页）；真调同会话 tools/call。
 * 结束时尽量 DELETE 会话（服务端可 405）。异常文案不带地址与响应体，避免令牌泄漏。
 * </p>
 * 与 {@link McpSseClient} 并列：本类不走 GET endpoint 事件，禁止混用旧 SSE 握手。
 */
public class McpHttpClient implements McpClient {

    private static final String ACCEPT = "application/json, text/event-stream";
    private static final String HEADER_SESSION = "Mcp-Session-Id";
    private static final String HEADER_PROTOCOL = "MCP-Protocol-Version";

    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final long readTimeoutMs;

    /**
     * @param httpClient    连接超时由调用方设置
     * @param json          JSON-RPC
     * @param readTimeoutMs 从建连到收完的总上限
     */
    public McpHttpClient(HttpClient httpClient, ObjectMapper json, long readTimeoutMs) {
        this.httpClient = httpClient;
        this.json = json;
        this.readTimeoutMs = Math.max(readTimeoutMs, 1);
    }

    /**
     * {@inheritDoc}
     * 最多翻 20 页 nextCursor，防止游标死循环。
     */
    @Override
    public List<McpToolView> listTools(String baseUrl, String authHeader) {
        return withSession(baseUrl, authHeader, (endpoint, auth, sessionId, protocol, deadline, nextId) -> {
            List<McpToolView> all = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                long id = nextId.getAndIncrement();
                JsonNode result = rpc(endpoint, auth, sessionId, protocol,
                        McpJson.toolsList(json, id, cursor), id, deadline);
                all.addAll(McpJson.tools(json, result));
                cursor = cursorOf(result);
                if (cursor == null) {
                    break;
                }
            }
            return all;
        });
    }

    /**
     * {@inheritDoc}
     * 每次调用新建会话；试跑频次低，不在此缓存长连接。
     */
    @Override
    public String callTool(String baseUrl, String authHeader, String toolName, String argumentsJson) {
        String name = toolName == null ? "" : toolName.trim();
        if (name.isEmpty()) {
            throw new McpCallException("MCP 工具名为空");
        }
        return withSession(baseUrl, authHeader, (endpoint, auth, sessionId, protocol, deadline, nextId) -> {
            long id = nextId.getAndIncrement();
            JsonNode result = rpc(endpoint, auth, sessionId, protocol,
                    McpJson.toolsCall(json, id, name, argumentsJson), id, deadline);
            return McpJson.callOutput(json, result);
        });
    }

    /**
     * 完成 Streamable HTTP 握手后执行业务 RPC；结束后尝试关闭会话。
     */
    private <T> T withSession(String baseUrl, String authHeader, SessionWork<T> work) {
        URI endpoint = parseBase(baseUrl);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs);
        AtomicLong nextId = new AtomicLong(1);
        long initId = nextId.getAndIncrement();
        // 先不带 session；initialize 成功后再带 Mcp-Session-Id
        InitOutcome init = postInit(endpoint, authHeader, McpJson.initialize(json, McpJson.PROTOCOL_HTTP), initId, deadline);
        String sessionId = init.sessionId();
        String protocol = init.protocolVersion();
        try {
            rpc(endpoint, authHeader, sessionId, protocol, McpJson.initialized(json), null, deadline);
            return work.run(endpoint, authHeader, sessionId, protocol, deadline, nextId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpCallException("MCP 同步已中断");
        } finally {
            // 有会话则尽量 DELETE；405/失败忽略，不影响已拿到的结果
            endSessionQuietly(endpoint, authHeader, sessionId, protocol, deadline);
        }
    }

    private InitOutcome postInit(URI endpoint, String authHeader, ObjectNode body, long id, long deadline) {
        HttpResponse<String> response = sendPost(endpoint, authHeader, null, null, body, deadline);
        if (response.statusCode() >= 400) {
            throw new McpCallException("MCP 请求失败: HTTP " + response.statusCode());
        }
        String sessionId = header(response, HEADER_SESSION);
        JsonNode result = readRpcResult(response, id, deadline);
        String negotiated = text(result.get("protocolVersion"));
        String protocol = negotiated != null ? negotiated : McpJson.PROTOCOL_HTTP;
        return new InitOutcome(sessionId, protocol);
    }

    /**
     * id 为空时只发通知：2xx（含 202）即成功，不等 JSON-RPC result。
     */
    private JsonNode rpc(URI endpoint, String authHeader, String sessionId, String protocol,
                         ObjectNode body, Long id, long deadline) throws InterruptedException {
        HttpResponse<String> response = sendPost(endpoint, authHeader, sessionId, protocol, body, deadline);
        if (id == null) {
            if (response.statusCode() >= 400) {
                throw new McpCallException("MCP 请求失败: HTTP " + response.statusCode());
            }
            return null;
        }
        if (response.statusCode() >= 400) {
            throw new McpCallException("MCP 请求失败: HTTP " + response.statusCode());
        }
        return readRpcResult(response, id, deadline);
    }

    private HttpResponse<String> sendPost(URI endpoint, String authHeader, String sessionId, String protocol,
                                          ObjectNode body, long deadline) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        AuthHeaders.apply(builder, authHeader);
        // 握手后的请求才带会话与协议版本头
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header(HEADER_SESSION, sessionId.trim());
        }
        if (protocol != null && !protocol.isBlank()) {
            builder.header(HEADER_PROTOCOL, protocol.trim());
        }
        return send(builder.build(), deadline);
    }

    /**
     * 按 Content-Type 拆 JSON 直出或 SSE data 帧；只取匹配 id 的 JSON-RPC 响应。
     */
    private JsonNode readRpcResult(HttpResponse<String> response, long id, long deadline) {
        String contentType = header(response, "Content-Type");
        String body = response.body() == null ? "" : response.body();
        if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
            return awaitIdFromSse(body, id, deadline);
        }
        JsonNode posted = McpJson.tryParse(json, body);
        if (posted != null && posted.has("id") && posted.get("id").asLong() == id) {
            return McpJson.unwrap(posted);
        }
        // 少数服务端误标 Content-Type 但仍是 JSON
        if (posted != null && posted.has("result")) {
            return McpJson.unwrap(posted);
        }
        throw new McpCallException("MCP 返回无法解析");
    }

    private JsonNode awaitIdFromSse(String body, long id, long deadline) {
        BlockingQueue<SseFrames.Frame> frames = new LinkedBlockingQueue<>();
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            SseFrames.pump(in, frames);
        } catch (Exception ex) {
            throw new McpCallException("MCP SSE 读取中断");
        }
        while (System.nanoTime() < deadline) {
            SseFrames.Frame frame = frames.poll();
            if (frame == null) {
                break;
            }
            if ("error".equals(frame.event())) {
                throw new McpCallException("MCP SSE 读取中断");
            }
            JsonNode node = McpJson.tryParse(json, frame.data());
            if (node != null && node.has("id") && node.get("id").asLong() == id) {
                return McpJson.unwrap(node);
            }
        }
        throw new McpCallException("MCP 同步超时");
    }

    private void endSessionQuietly(URI endpoint, String authHeader, String sessionId, String protocol, long deadline) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).DELETE();
            AuthHeaders.apply(builder, authHeader);
            builder.header(HEADER_SESSION, sessionId.trim());
            if (protocol != null && !protocol.isBlank()) {
                builder.header(HEADER_PROTOCOL, protocol.trim());
            }
            send(builder.build(), deadline);
        } catch (RuntimeException ignored) {
            // 会话清理失败不影响 list/call 已成功结果
        }
    }

    private HttpResponse<String> send(HttpRequest request, long deadline) {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            return future.get(millisLeft(deadline), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new McpCallException("MCP 同步超时");
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpCallException("MCP 同步已中断");
        } catch (ExecutionException ex) {
            throw new McpCallException("MCP HTTP 连接失败");
        }
    }

    private long millisLeft(long deadline) {
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left <= 0) {
            throw new McpCallException("MCP 同步超时");
        }
        return left;
    }

    private URI parseBase(String baseUrl) {
        try {
            return URI.create(baseUrl);
        } catch (IllegalArgumentException ex) {
            throw new McpCallException("MCP 地址不合法");
        }
    }

    private String cursorOf(JsonNode result) {
        JsonNode cursor = result.get("nextCursor");
        if (cursor == null || cursor.isNull()) {
            return null;
        }
        String text = cursor.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private record InitOutcome(String sessionId, String protocolVersion) {
    }

    /**
     * 握手完成后的业务 RPC；由调用方决定发 list 还是 call。
     */
    @FunctionalInterface
    private interface SessionWork<T> {
        T run(URI endpoint, String authHeader, String sessionId, String protocol, long deadline, AtomicLong nextId)
                throws InterruptedException;
    }
}

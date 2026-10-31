package com.agenthub.tool.infra.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * MCP HTTP+SSE 客户端。所属域：tool 的 infra。
 * 按 2024-11-05：GET baseUrl 收 {@code event: endpoint}，再向该地址 POST JSON-RPC。
 * 同步：initialize → notifications/initialized → tools/list（可翻页）。
 * 真调：同一握手后发 tools/call。响应优先取 POST 正文，否则等 SSE 的 message 帧。
 * 异常文案不带地址和响应体，避免把查询串里的令牌写进日志。
 */
public class McpSseClient implements McpClient {

    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final long readTimeoutMs;

    /**
     * @param httpClient    连接超时由调用方设置
     * @param json          JSON-RPC
     * @param readTimeoutMs 从建连到收完的总上限
     */
    public McpSseClient(HttpClient httpClient, ObjectMapper json, long readTimeoutMs) {
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
        return withSession(baseUrl, authHeader, (postUri, auth, frames, deadline, nextId) -> {
            List<McpToolView> all = new ArrayList<>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                long id = nextId.getAndIncrement();
                JsonNode result = rpc(postUri, auth, McpJson.toolsList(json, id, cursor), id, frames, deadline);
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
     * 每次调用新建 SSE 会话；试跑频次低，不在此缓存长连接。
     */
    @Override
    public String callTool(String baseUrl, String authHeader, String toolName, String argumentsJson) {
        String name = toolName == null ? "" : toolName.trim();
        if (name.isEmpty()) {
            throw new McpCallException("MCP 工具名为空");
        }
        return withSession(baseUrl, authHeader, (postUri, auth, frames, deadline, nextId) -> {
            long id = nextId.getAndIncrement();
            JsonNode result = rpc(postUri, auth, McpJson.toolsCall(json, id, name, argumentsJson), id, frames, deadline);
            return McpJson.callOutput(json, result);
        });
    }

    /**
     * 打开 SSE、完成 initialize，再执行业务 RPC；结束后关掉流。
     */
    private <T> T withSession(String baseUrl, String authHeader, SessionWork<T> work) {
        URI base = parseBase(baseUrl);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs);
        HttpResponse<InputStream> opened = openSse(base, authHeader, deadline);
        BlockingQueue<SseFrames.Frame> frames = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> SseFrames.pump(opened.body(), frames), "mcp-sse-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            SseFrames.Frame endpoint = awaitEvent(frames, "endpoint", deadline);
            String data = endpoint.data() == null ? "" : endpoint.data().trim();
            if (data.isEmpty()) {
                throw new McpCallException("MCP 未返回消息地址");
            }
            URI postUri = base.resolve(data);
            AtomicLong nextId = new AtomicLong(1);
            long initId = nextId.getAndIncrement();
            rpc(postUri, authHeader, McpJson.initialize(json), initId, frames, deadline);
            rpc(postUri, authHeader, McpJson.initialized(json), null, frames, deadline);
            return work.run(postUri, authHeader, frames, deadline, nextId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpCallException("MCP 同步已中断");
        } finally {
            closeQuietly(opened.body());
        }
    }

    private HttpResponse<InputStream> openSse(URI base, String authHeader, long deadline) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base).header("Accept", "text/event-stream").GET();
        AuthHeaders.apply(builder, authHeader);
        HttpResponse<InputStream> opened = send(builder.build(), HttpResponse.BodyHandlers.ofInputStream(), deadline);
        if (opened.statusCode() != 200) {
            closeQuietly(opened.body());
            throw new McpCallException("MCP SSE 连接失败: HTTP " + opened.statusCode());
        }
        return opened;
    }

    /**
     * id 为空时只发通知，不等响应。POST 已带回对应 id 的 JSON 就直接用，否则等 SSE。
     */
    private JsonNode rpc(URI uri, String authHeader, ObjectNode body, Long id, BlockingQueue<SseFrames.Frame> frames, long deadline)
            throws InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        AuthHeaders.apply(builder, authHeader);
        HttpResponse<String> response = send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), deadline);
        if (response.statusCode() >= 400) {
            throw new McpCallException("MCP 请求失败: HTTP " + response.statusCode());
        }
        if (id == null) {
            return null;
        }
        JsonNode posted = McpJson.tryParse(json, response.body());
        if (posted != null && posted.has("id") && posted.get("id").asLong() == id) {
            return McpJson.unwrap(posted);
        }
        return awaitId(frames, id, deadline);
    }

    private SseFrames.Frame awaitEvent(BlockingQueue<SseFrames.Frame> frames, String event, long deadline) throws InterruptedException {
        while (System.nanoTime() < deadline) {
            SseFrames.Frame frame = frames.poll(millisLeft(deadline), TimeUnit.MILLISECONDS);
            if (frame == null) {
                break;
            }
            if ("error".equals(frame.event())) {
                throw new McpCallException("MCP SSE 读取中断");
            }
            if (event.equalsIgnoreCase(frame.event())) {
                return frame;
            }
        }
        throw new McpCallException("MCP 同步超时");
    }

    private JsonNode awaitId(BlockingQueue<SseFrames.Frame> frames, long id, long deadline) throws InterruptedException {
        while (System.nanoTime() < deadline) {
            SseFrames.Frame frame = frames.poll(millisLeft(deadline), TimeUnit.MILLISECONDS);
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

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler, long deadline) {
        CompletableFuture<HttpResponse<T>> future = httpClient.sendAsync(request, handler);
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
            throw new McpCallException("MCP SSE 连接失败");
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

    private void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // 关闭 SSE 只为放开读线程，失败不影响已经拿到的列表
        }
    }

    /**
     * 握手完成后的业务 RPC；由调用方决定发 list 还是 call。
     */
    @FunctionalInterface
    private interface SessionWork<T> {
        T run(URI postUri, String authHeader, BlockingQueue<SseFrames.Frame> frames, long deadline, AtomicLong nextId)
                throws InterruptedException;
    }
}

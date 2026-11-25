package com.agenthub.tool.infra.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 HttpServer 模拟 MCP Streamable HTTP：initialize 带会话、tools/list、tools/call。
 * 失败文案不能带回鉴权头。
 */
class McpHttpClientTest {

    private static final String SECRET = "http-secret-token";
    private static final String SESSION = "sess-http-1";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenSession = new AtomicReference<>();
    private final AtomicReference<String> lastCallBody = new AtomicReference<>();
    private final AtomicInteger lists = new AtomicInteger();

    private HttpServer server;
    private ExecutorService pool;
    private McpHttpClient client;

    /**
     * 随机端口；单端点 POST 处理全部 JSON-RPC。
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/mcp", this::handle);
        server.start();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1000)).build();
        client = new McpHttpClient(http, json, 4000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        pool.shutdownNow();
    }

    /**
     * list：握手 → 分页 tools/list；后续请求须带回 Mcp-Session-Id。
     */
    @Test
    void listsToolsAcrossCursor() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        List<McpToolView> tools = client.listTools(base, "Authorization: Bearer " + SECRET);
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/list"), List.copyOf(methods));
        assertEquals("Bearer " + SECRET, seenAuth.get());
        assertEquals(SESSION, seenSession.get());
        assertEquals(2, tools.size());
        assertEquals("echo", tools.get(0).name());
        assertEquals("ping", tools.get(1).name());
        assertFalse(tools.toString().contains(SECRET));
    }

    /**
     * tools/call 须真实发出，并带回文本结果。
     */
    @Test
    void callToolReturnsTextContent() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        String out = client.callTool(base, "Authorization: Bearer " + SECRET, "system_user_list",
                "{\"page\":1}");
        assertTrue(methods.contains("initialize"));
        assertTrue(methods.contains("notifications/initialized"));
        assertTrue(methods.contains("tools/call"));
        assertEquals("user-a", out);
        assertTrue(lastCallBody.get().contains("system_user_list"));
        assertTrue(lastCallBody.get().contains("\"page\":1"));
        assertFalse(out.contains(SECRET));
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            seenAuth.set(auth);
        }
        String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (session != null) {
            seenSession.set(session);
        }
        // 会话结束 DELETE：直接 200，无 body
        if ("DELETE".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] raw = exchange.getRequestBody().readAllBytes();
        JsonNode node = json.readTree(raw);
        String rpcMethod = node.path("method").asText();
        methods.add(rpcMethod);
        if ("initialize".equals(rpcMethod)) {
            long id = node.path("id").asLong();
            byte[] body = result(id, "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"serverInfo\":{\"name\":\"mock-http\",\"version\":\"0\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Mcp-Session-Id", SESSION);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        if ("notifications/initialized".equals(rpcMethod)) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        if ("tools/list".equals(rpcMethod)) {
            long id = node.path("id").asLong();
            String payload;
            if (lists.incrementAndGet() == 1) {
                payload = "{\"tools\":[{\"name\":\"echo\",\"description\":\"say\",\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"p2\"}";
            } else {
                payload = "{\"tools\":[{\"name\":\"ping\",\"title\":\"Ping\",\"description\":\"pong\",\"inputSchema\":{\"type\":\"object\"}}]}";
            }
            writeJson(exchange, result(id, payload));
            return;
        }
        if ("tools/call".equals(rpcMethod)) {
            lastCallBody.set(new String(raw, StandardCharsets.UTF_8));
            long id = node.path("id").asLong();
            writeJson(exchange, result(id, "{\"content\":[{\"type\":\"text\",\"text\":\"user-a\"}],\"isError\":false}"));
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private void writeJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String result(long id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
    }
}

package com.agenthub.tool.infra.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本地 HttpServer 模拟 MCP SSE：endpoint 事件、initialize、分页 tools/list、tools/call。
 * 失败文案不能带回鉴权头或响应正文。
 */
class McpSseClientTest {

    private static final String SECRET = "secret-token";

    private final ObjectMapper json = new ObjectMapper();
    private final LinkedBlockingQueue<String> outbound = new LinkedBlockingQueue<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> lastCallBody = new AtomicReference<>();
    private final AtomicInteger lists = new AtomicInteger();

    private HttpServer server;
    private ExecutorService pool;
    private ThreadPoolTaskExecutor readerExecutor;
    private McpSseClient client;

    /**
     * 随机端口。GET 和 POST 必须能并行，否则 SSE 会把同步堵死。
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1000)).build();
        // 与生产一致：读流走 ThreadPoolTaskExecutor，而不是裸 new Thread
        readerExecutor = new ThreadPoolTaskExecutor();
        readerExecutor.setCorePoolSize(2);
        readerExecutor.setMaxPoolSize(4);
        readerExecutor.setQueueCapacity(16);
        readerExecutor.setThreadNamePrefix("mcp-reader-test-");
        readerExecutor.setDaemon(true);
        readerExecutor.initialize();
        client = new McpSseClient(http, json, 4000, readerExecutor);
    }

    /**
     * 停掉端口和还堵在 SSE 上的线程。
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
        pool.shutdownNow();
        readerExecutor.shutdown();
    }

    /**
     * 两页工具都要回来，并且请求顺序符合 SSE 握手。
     */
    @Test
    void listsToolsAcrossCursor() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
        List<McpToolView> tools = client.listTools(base, "Authorization: Bearer " + SECRET);
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/list"), List.copyOf(methods));
        assertEquals("Bearer " + SECRET, seenAuth.get());
        assertEquals(2, tools.size());
        assertEquals("echo", tools.get(0).name());
        assertEquals("ping", tools.get(1).name());
        assertEquals("Ping", tools.get(1).title());
        assertFalse(tools.toString().contains(SECRET));
    }

    /**
     * HTTP 失败只留状态码，不把正文里的令牌带回。
     */
    @Test
    void httpFailureDoesNotEchoSecret() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/fail";
        McpCallException ex = assertThrows(McpCallException.class, () -> client.listTools(base, "Authorization: Bearer " + SECRET));
        assertTrue(ex.getMessage().contains("HTTP 500"));
        assertFalse(ex.getMessage().contains(SECRET));
        assertFalse(ex.getMessage().contains("nope"));
    }

    /**
     * tools/call 须真实发出，并带回文本结果。
     */
    @Test
    void callToolReturnsTextContent() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
        String out = client.callTool(base, "Authorization: Bearer " + SECRET, "system_user_list",
                "{\"page\":1}");
        assertEquals(List.of("initialize", "notifications/initialized", "tools/call"), List.copyOf(methods));
        assertEquals("user-a", out);
        assertTrue(lastCallBody.get().contains("system_user_list"));
        assertTrue(lastCallBody.get().contains("\"page\":1"));
        assertFalse(out.contains(SECRET));
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            seenAuth.set(auth);
        }
        if ("/fail".equals(path)) {
            byte[] body = ("nope " + SECRET).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        if ("/sse".equals(path)) {
            writeSse(exchange);
            return;
        }
        if ("/message".equals(path)) {
            acceptRpc(exchange);
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private void writeSse(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (!Thread.currentThread().isInterrupted()) {
                String message = outbound.poll(2, TimeUnit.SECONDS);
                if (message == null) {
                    continue;
                }
                out.write(("event: message\ndata: " + message + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptRpc(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        JsonNode node = json.readTree(raw);
        String method = node.path("method").asText();
        methods.add(method);
        if ("initialize".equals(method)) {
            outbound.offer(result(node.path("id").asLong(), "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"mock\",\"version\":\"0\"}}"));
        } else if ("tools/list".equals(method)) {
            long id = node.path("id").asLong();
            if (lists.incrementAndGet() == 1) {
                outbound.offer(result(id, "{\"tools\":[{\"name\":\"echo\",\"description\":\"say\",\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"p2\"}"));
            } else {
                outbound.offer(result(id, "{\"tools\":[{\"name\":\"ping\",\"title\":\"Ping\",\"description\":\"pong\",\"inputSchema\":{\"type\":\"object\"}}]}"));
            }
        } else if ("tools/call".equals(method)) {
            lastCallBody.set(new String(raw, StandardCharsets.UTF_8));
            long id = node.path("id").asLong();
            outbound.offer(result(id, "{\"content\":[{\"type\":\"text\",\"text\":\"user-a\"}],\"isError\":false}"));
        }
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
    }

    private String result(long id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
    }
}

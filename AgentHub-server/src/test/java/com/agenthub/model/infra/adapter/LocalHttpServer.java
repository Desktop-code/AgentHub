package com.agenthub.model.infra.adapter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地 mock HTTP 服务：只服务于本机单测，不随仓库分发（src/test 已在 .gitignore）。
 * 记录路径与请求体，按测试预设返回 JSON 或字节，避免单测打真实模型。
 */
final class LocalHttpServer implements AutoCloseable {

    record Recorded(String path, String body, Map<String, String> headers) {
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private volatile Response fallback = new Response(200, "{}".getBytes(StandardCharsets.UTF_8), "application/json");

    LocalHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requestCount() {
        return requests.size();
    }

    List<Recorded> requests() {
        return List.copyOf(requests);
    }

    Recorded lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    void respond(String path, int status, String body) {
        responses.put(path, new Response(status, body.getBytes(StandardCharsets.UTF_8), "application/json"));
    }

    void respondBytes(String path, int status, byte[] body, String contentType) {
        responses.put(path, new Response(status, body, contentType));
    }

    void fallback(int status, String body) {
        this.fallback = new Response(status, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
        requests.add(new Recorded(path, body, headers));
        Response response = responses.getOrDefault(path, fallback);
        exchange.getResponseHeaders().add("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), response.body().length);
        exchange.getResponseBody().write(response.body());
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private record Response(int status, byte[] body, String contentType) {
    }
}

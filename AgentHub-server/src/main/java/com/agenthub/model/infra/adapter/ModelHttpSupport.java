package com.agenthub.model.infra.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 探活共用的 HTTP 发送与脱敏。所属域：model。
 * 各协议客户端自己决定 URL 和报文，避免把厂商差异塞进一个类。
 */
@Component
public class ModelHttpSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final ModelHttpProperties properties;

    /**
     * @param httpClient   带连接超时的客户端
     * @param properties   读超时
     */
    public ModelHttpSupport(HttpClient httpClient, ModelHttpProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * 拼接 baseUrl 和相对路径，去掉重复斜杠。
     *
     * @param baseUrl 供应商地址
     * @param path    以 / 开头的路径
     * @return 完整 URL
     */
    public String join(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String suffix = path.startsWith("/") ? path : "/" + path;
        return base + suffix;
    }

    /**
     * @param value 任意对象
     * @return JSON 文本
     */
    public String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("探活报文无法序列化");
        }
    }

    /**
     * 发送 JSON，并把状态码收成脱敏结果。响应体只在失败时截一段，且去掉 Key。
     *
     * @param url       完整地址
     * @param apiKey    用于脱敏，也会放进 Authorization
     * @param body      JSON
     * @param okMessage 2xx 时返回的说明
     * @return 探活结果
     */
    public ProbeResult postJson(String url, String apiKey, String body, String okMessage) {
        HttpRequest request = request(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, apiKey, okMessage);
    }

    /**
     * 带额外头的 POST。Anthropic 不用 Bearer。
     *
     * @param url       完整地址
     * @param headers   额外头，不要把这些头打进日志
     * @param apiKey    只用于从错误文本里抹掉
     * @param body      JSON
     * @param okMessage 成功说明
     * @return 探活结果
     */
    public ProbeResult postJson(String url, Map<String, String> headers, String apiKey, String body, String okMessage) {
        HttpRequest.Builder builder = request(url).header("Content-Type", "application/json");
        headers.forEach(builder::header);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return send(request, apiKey, okMessage);
    }

    /**
     * 降级探活：只确认地址能建立 HTTP，并带上鉴权头。
     *
     * @param url       供应商地址
     * @param apiKey    Bearer 的值
     * @param okMessage 2xx 时的说明，应写明这是降级检测
     * @return 探活结果
     */
    public ProbeResult get(String url, String apiKey, String okMessage) {
        HttpRequest request = request(url)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        return send(request, apiKey, okMessage);
    }

    /**
     * @param url     供应商地址
     * @param headers 鉴权头
     * @param apiKey  脱敏用
     * @param okMessage 成功说明
     * @return 探活结果
     */
    public ProbeResult get(String url, Map<String, String> headers, String apiKey, String okMessage) {
        HttpRequest.Builder builder = request(url);
        headers.forEach(builder::header);
        return send(builder.GET().build(), apiKey, okMessage);
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(properties.getReadTimeoutMs(), 1)));
    }

    private ProbeResult send(HttpRequest request, String apiKey, String okMessage) {
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = elapsed(started);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new ProbeResult(true, latency, okMessage);
            }
            if (status == 401 || status == 403) {
                return new ProbeResult(false, latency, "鉴权失败");
            }
            if (status == 404) {
                return new ProbeResult(false, latency, "模型或路径不存在");
            }
            String brief = brief(response.body(), apiKey);
            String message = brief.isBlank() ? "HTTP " + status : "HTTP " + status + " " + brief;
            return new ProbeResult(false, latency, message);
        } catch (HttpTimeoutException ex) {
            return new ProbeResult(false, elapsed(started), "连接超时");
        } catch (IOException ex) {
            return new ProbeResult(false, elapsed(started), "目标不可达");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, elapsed(started), "探活被中断");
        } catch (IllegalArgumentException ex) {
            return new ProbeResult(false, elapsed(started), "baseUrl 不合法");
        }
    }

    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * 失败说明最多 180 字，并抹掉本次使用的 Key。不记录完整响应。
     */
    private String brief(String body, String apiKey) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String text = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (apiKey != null && !apiKey.isBlank()) {
            text = text.replace(apiKey, "***");
        }
        if (text.length() > 180) {
            text = text.substring(0, 180);
        }
        return text;
    }
}

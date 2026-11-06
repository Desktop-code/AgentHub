package com.agenthub.model.infra.adapter;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.text.SecretBrief;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 语音出站的 HTTP 发送与脱敏。所属域：model 的 infra。
 *
 * <p>与 {@link ModelHttpSupport} 分开的原因：探活只关心「通不通」，返回 {@link ProbeResult}；
 * 语音出站要拿响应正文（识别文字）和原始字节（合成音频），失败还要给出可读中文原因。
 * 两者混在一个类里会把「只回脱敏结果」的探活语义弄脏。
 *
 * <p>安全边界（硬性）：
 * <ul>
 *   <li>非 2xx 的响应体只经 {@link SecretBrief#brief} 折行、抹掉本次 Key、截断后才进异常文案；</li>
 *   <li>任何路径都不打印 Key、音频二进制或完整响应体；</li>
 *   <li>音频只在内存里流转，不落盘、不进对象存储。</li>
 * </ul>
 * 线程：无状态可并发；每次调用自建请求对象。调用方需在阻塞线程调用（不是事件循环）。
 */
@Component
public class SpeechHttpSupport {

    private static final String CRLF = "\r\n";
    /** multipart 分隔串前缀；每次请求带随机后缀，避免与音频内容里的字节序列冲突 */
    private static final String BOUNDARY_PREFIX = "----AgentHubSpeech";

    private final HttpClient httpClient;
    private final ModelHttpProperties properties;

    /**
     * @param httpClient 与探活共用的 JDK HttpClient（连接超时已在 Bean 里设定）
     * @param properties 语音读超时
     */
    public SpeechHttpSupport(HttpClient httpClient, ModelHttpProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * 拼接 baseUrl 与相对路径，去掉重复斜杠。
     *
     * @param baseUrl 供应商地址（通常已带 /v1）
     * @param path    以 / 开头的路径
     * @return 完整 URL
     */
    public String join(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型供应商未配置 baseUrl");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String suffix = path.startsWith("/") ? path : "/" + path;
        return base + suffix;
    }

    /**
     * POST JSON 并取原始音频字节（合成用）。
     *
     * @param url      完整地址
     * @param apiKey   已解密 Key，用于鉴权与脱敏
     * @param bodyJson JSON 正文
     * @return 2xx 的响应字节
     * @throws BizException 非 2xx、超时或不可达；文案已脱敏
     */
    public byte[] postJsonForBytes(String url, String apiKey, String bodyJson) {
        HttpRequest request = request(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();
        return send(request, apiKey);
    }

    /**
     * POST multipart（识别用）。表单只带模型名等文本字段 + 一个音频文件。
     *
     * @param url      完整地址
     * @param apiKey   已解密 Key
     * @param fields   额外表单字段（如 model / response_format）
     * @param fileName 上传文件名，扩展名会影响上游对格式的判断
     * @param mimeType 音频 MIME
     * @param audio    音频字节
     * @return 2xx 的响应正文（UTF-8）
     * @throws BizException 非 2xx、超时或不可达；文案已脱敏
     */
    public String postMultipart(String url, String apiKey, Map<String, String> fields,
                                String fileName, String mimeType, byte[] audio) {
        String boundary = BOUNDARY_PREFIX + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartBody(boundary, fields, fileName, mimeType, audio);
        HttpRequest request = request(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return new String(send(request, apiKey), StandardCharsets.UTF_8);
    }

    /**
     * 发请求并返回 2xx 字节；失败统一转成中文业务异常。
     *
     * @param request 已构造的请求
     * @param apiKey  脱敏用 Key
     * @return 响应字节
     */
    private byte[] send(HttpRequest request, String apiKey) {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response.body() == null ? new byte[0] : response.body();
            }
            throw httpFailure(status, response.body(), apiKey);
        } catch (HttpTimeoutException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语音服务超时，请稍后重试");
        } catch (IOException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "语音服务不可达，请检查模型供应商地址");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.BAD_REQUEST, "语音请求被中断，请重试");
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型供应商 baseUrl 不合法");
        }
    }

    /**
     * 非 2xx 的文案。401/403 与 404 单独给白话，其余带上脱敏摘要。
     *
     * @param status 状态码
     * @param body   响应体
     * @param apiKey 本次 Key
     * @return 业务异常
     */
    private BizException httpFailure(int status, byte[] body, String apiKey) {
        if (status == 401 || status == 403) {
            return new BizException(ErrorCode.BAD_REQUEST, "语音模型凭证被拒绝，请检查模型 Key");
        }
        if (status == 404) {
            return new BizException(ErrorCode.BAD_REQUEST,
                    "语音接口路径不存在（HTTP 404），请确认协议与 baseUrl 是否支持语音接口");
        }
        String brief = SecretBrief.brief(body == null ? null : new String(body, StandardCharsets.UTF_8), apiKey);
        return new BizException(ErrorCode.BAD_REQUEST,
                brief.isBlank() ? "语音服务返回 HTTP " + status : "语音服务返回 HTTP " + status + " " + brief);
    }

    /**
     * 组装 multipart 正文。字段在前、文件在后；换行固定 CRLF 以兼容各家实现。
     *
     * @param boundary 分隔串
     * @param fields   文本字段
     * @param fileName 文件名
     * @param mimeType 文件 MIME
     * @param content  文件字节
     * @return 完整请求体
     */
    private byte[] multipartBody(String boundary, Map<String, String> fields,
                                 String fileName, String mimeType, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 1024);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            writeText(out, "--" + boundary + CRLF
                    + "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"" + CRLF + CRLF
                    + entry.getValue() + CRLF);
        }
        writeText(out, "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"" + CRLF
                + "Content-Type: " + mimeType + CRLF + CRLF);
        out.write(content, 0, content.length);
        writeText(out, CRLF + "--" + boundary + "--" + CRLF);
        return out.toByteArray();
    }

    private void writeText(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * 构造带超时的请求。baseUrl 由用户配置，非法时转成业务错误而不是 500。
     *
     * @param url 完整地址
     * @return 请求构造器
     * @throws BizException 地址非法
     */
    private HttpRequest.Builder request(String url) {
        try {
            return HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(properties.getSpeechTimeoutMs(), 1)));
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模型供应商 baseUrl 不合法");
        }
    }
}

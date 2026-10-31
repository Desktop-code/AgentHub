package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.ModelProviderAdapter;
import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedChatResponse;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * OpenAI 兼容协议的模型适配器。所属域：runtime（模型出站 ACL）。
 * 覆盖 DeepSeek / GLM / Ollama / vLLM 与多数中转：它们都遵循 {@code POST {baseUrl}/chat/completions}
 * 与 {@code choices[].delta} 流式语义，差异只在部分可选字段。
 * 职责边界：本类只管「发什么请求 + 把字节流交给谁」；请求体映射在 {@link OpenAiRequestMapper}，
 * 响应映射在 {@link OpenAiResponseMapper}，流式状态机在 {@link OpenAiStreamDecoder}，
 * SSE 切帧与 done 兜底在 {@link StreamAssembler}——三家 Adapter 共用同一套收尾骨架。
 * 约束：
 * 1. 只有本类与上述三个映射类允许出现 choices / delta / tool_calls 等厂商字段名；
 * 2. 不持有可变状态、可并发复用：每条订阅新建独立的解码器；
 * 3. 凭证由 {@link OpenAICompatibleConfig} 在调用边界传入，不进入 Unified* 模型，也不进日志；
 * 4. 不做重试与供应商降级切换，那是 ModelGateway 的职责。
 */
public class OpenAICompatibleAdapter implements ModelProviderAdapter {

    private final OpenAICompatibleConfig config;
    private final WebClient webClient;
    private final ObjectMapper json;
    /** 请求体映射器，无状态可复用 */
    private final OpenAiRequestMapper requestMapper;
    /** 非流式响应映射器，无状态可复用 */
    private final OpenAiResponseMapper responseMapper;

    /**
     * @param config    连接与凭证配置，一次模型实例一份
     * @param webClient 已配置 baseUrl 的客户端；由调用方注入便于单测替换
     * @param json      序列化器，复用容器配置
     */
    public OpenAICompatibleAdapter(OpenAICompatibleConfig config, WebClient webClient, ObjectMapper json) {
        this.config = config;
        this.webClient = webClient;
        this.json = json;
        this.requestMapper = new OpenAiRequestMapper(json);
        this.responseMapper = new OpenAiResponseMapper(json);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getProviderName() {
        return config.getProviderName();
    }

    /**
     * 流式对话。链路分三段：请求映射 → SSE 逐行解码 → 收尾补 done。
     * done 兜底（正常结束 / 缺 [DONE] 截断 / 上游报错 / 读超时 / 客户端取消）统一在
     * {@link StreamAssembler#assemble} 内完成，本方法只负责把上游字节流交出去。
     *
     * @param request 统一请求
     * @return 事件流；正常与异常路径末尾都有且仅有一个 type=done
     */
    @Override
    public Flux<UnifiedStreamEvent> chatStream(UnifiedChatRequest request) {
        Flux<DataBuffer> body = webClient.post()
                .uri(chatUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestMapper.build(request, config.getModelName(), true))
                .retrieve()
                // 非 2xx 统一转成带状态码的异常，避免把响应体原文抛给前端
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(text -> UpstreamFailures.http(response.statusCode().value(), text)))
                .bodyToFlux(DataBuffer.class)
                // 两帧之间长时间无数据即视为读超时，必须走 TIMEOUT 收尾而不是一直挂着
                .timeout(config.getStreamIdleTimeout(), Flux.defer(() ->
                        Flux.error(UpstreamFailures.idleTimeout(config.getStreamIdleTimeout()))));

        // 每次订阅新建解码器：同一 Adapter 实例可能被多路复用，流内状态绝不能共享
        return StreamAssembler.assemble(body, () -> new OpenAiStreamDecoder(json));
    }

    /**
     * 非流式对话。供 Router 判定、结构化抽取等一次性场景使用。
     * 本方法是阻塞语义（接口约定），调用方需在弹性调度器上执行，避免占用事件循环线程。
     *
     * @param request 统一请求
     * @return 统一响应
     */
    @Override
    public UnifiedChatResponse chat(UnifiedChatRequest request) {
        return webClient.post()
                .uri(chatUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(requestMapper.build(request, config.getModelName(), false))
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(text -> UpstreamFailures.http(response.statusCode().value(), text)))
                .bodyToMono(String.class)
                .map(responseMapper::toResponse)
                .block(config.getRequestTimeout());
    }

    /**
     * 拼对话地址。把 baseUrl 与 chatPath 在这里合成为绝对 URI：
     * 部分兼容中转的 baseUrl 带版本段（{@code https://x/compatible-mode/v1}），
     * 若交给 WebClient 的 baseUrl + 绝对路径拼接，路径段会被整体替换而丢掉 {@code /v1}，
     * 统一在这里拼绝对地址可避免这个坑，也让单测注入带 baseUrl 的客户端时行为不变。
     *
     * @return 完整请求地址
     */
    private String chatUri() {
        String base = config.getBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = config.getChatPath() == null ? "" : config.getChatPath().trim();
        if (path.isEmpty()) {
            return base;
        }
        return path.startsWith("/") ? base + path : base + "/" + path;
    }
}

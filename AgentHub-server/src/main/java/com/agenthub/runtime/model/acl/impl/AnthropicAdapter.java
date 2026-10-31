package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.ModelProviderAdapter;
import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedChatResponse;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Anthropic Messages 协议的模型适配器。所属域：runtime（模型出站 ACL）。
 * 与 OpenAI 兼容族的差异（本类负责消化，业务侧不可见）：
 * <ol>
 *   <li><b>System 不在 messages 里</b>：必须是顶层 {@code system} 字段；messages 里出现 system 角色会被上游拒绝，
 *       故多条 system 消息需合并为一段文本（顺序保留）；</li>
 *   <li><b>鉴权头不同</b>：{@code x-api-key} + 必填 {@code anthropic-version}，不是 {@code Authorization: Bearer}；</li>
 *   <li><b>工具定义</b>：顶层 {@code tools[]} 只含 name/description/input_schema，没有 function 包一层；</li>
 *   <li><b>工具结果</b>：不是 role=tool 消息，而是 user 消息里的 {@code tool_result} content block；
 *       assistant 的工具调用是 {@code tool_use} block；</li>
 *   <li><b>流式</b>：结构化事件（content_block_start/delta/stop + message_delta + message_stop），没有 {@code [DONE]}；</li>
 *   <li>{@code max_tokens} 是必填字段，缺省时给一个安全值，否则上游直接 400。</li>
 * </ol>
 * 职责边界：本类只管「发什么请求 + 把字节流交给谁」；请求体映射在 {@link AnthropicRequestMapper}，
 * 响应映射在 {@link AnthropicResponseMapper}，流式状态机在 {@link AnthropicStreamDecoder}，
 * done 兜底在 {@link StreamAssembler}。这样每个类都短到可以逐行复核。
 * 约束：
 * 1. 厂商字段名（system / content blocks / tool_use / stop_reason …）只允许出现在本类与上述三个映射类中；
 * 2. 不持有可变状态、可并发复用；凭证只在构造时从 {@link AdapterEndpointConfig} 取用；
 * 3. 不做重试与降级切换（ModelGateway 职责）；不做 prompt caching / files 等本期未要求的边角能力。
 */
public class AnthropicAdapter implements ModelProviderAdapter {

    /** Anthropic API 版本头值。协议变更时才改，属于适配器常量而非业务配置 */
    private static final String API_VERSION = "2023-06-01";
    /** 版本头名 */
    private static final String API_VERSION_HEADER = "anthropic-version";
    /** 鉴权头名；Anthropic 不认 Authorization: Bearer */
    private static final String API_KEY_HEADER = "x-api-key";
    /** Messages 接口路径（相对版本根） */
    private static final String MESSAGES_PATH = "/messages";
    /** baseUrl 未带版本段时补的前缀 */
    private static final String VERSION_SEGMENT = "/v1";
    /** 默认供应商标识 */
    private static final String DEFAULT_PROVIDER = "anthropic";

    private final AdapterEndpointConfig config;
    private final WebClient webClient;
    private final ObjectMapper json;
    /** 请求体映射器，无状态可复用 */
    private final AnthropicRequestMapper requestMapper;
    /** 非流式响应映射器，无状态可复用 */
    private final AnthropicResponseMapper responseMapper;

    /**
     * @param config    连接与凭证配置，一次模型实例一份
     * @param webClient 已配置 baseUrl 的客户端；由调用方注入便于单测替换
     * @param json      序列化器，复用容器配置
     */
    public AnthropicAdapter(AdapterEndpointConfig config, WebClient webClient, ObjectMapper json) {
        this.config = config;
        this.webClient = webClient;
        this.json = json;
        this.requestMapper = new AnthropicRequestMapper(json);
        this.responseMapper = new AnthropicResponseMapper(json);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getProviderName() {
        String name = config.getProviderName();
        return (name == null || name.isBlank()) ? DEFAULT_PROVIDER : name;
    }

    /**
     * 流式对话。链路：请求映射 → SSE 逐帧解码 → 收尾补 done（{@link StreamAssembler} 统一承担）。
     * Anthropic 没有 [DONE]，正常结束靠 message_stop；截断时装配器仍会补 done，不会挂住前端。
     *
     * @param request 统一请求
     * @return 事件流；正常与异常路径末尾都有且仅有一个 type=done
     */
    @Override
    public Flux<UnifiedStreamEvent> chatStream(UnifiedChatRequest request) {
        Flux<DataBuffer> body = webClient.post()
                .uri(messagesUri())
                .header(API_KEY_HEADER, config.getApiKey())
                .header(API_VERSION_HEADER, API_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(requestMapper.build(request, config.getModelName(), true))
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(text -> UpstreamFailures.http(response.statusCode().value(), text)))
                .bodyToFlux(DataBuffer.class)
                // 两帧之间长时间无数据即视为读超时，必须走 TIMEOUT 收尾而不是一直挂着
                .timeout(config.getStreamIdleTimeout(),
                        Flux.defer(() -> Flux.error(UpstreamFailures.idleTimeout(config.getStreamIdleTimeout()))));

        return StreamAssembler.assemble(body, () -> new AnthropicStreamDecoder(json));
    }

    /**
     * 非流式对话。阻塞语义，调用方需在弹性调度器上执行。
     *
     * @param request 统一请求
     * @return 统一响应
     */
    @Override
    public UnifiedChatResponse chat(UnifiedChatRequest request) {
        return webClient.post()
                .uri(messagesUri())
                .header(API_KEY_HEADER, config.getApiKey())
                .header(API_VERSION_HEADER, API_VERSION)
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
     * 拼 Messages 地址。用户可能填 https://api.anthropic.com，也可能填到 /v1，
     * 两种都要能拼对且不重复叠加版本段。
     *
     * @return 完整请求地址
     */
    private String messagesUri() {
        String base = config.getBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(VERSION_SEGMENT)) {
            return base + MESSAGES_PATH;
        }
        return base + VERSION_SEGMENT + MESSAGES_PATH;
    }
}

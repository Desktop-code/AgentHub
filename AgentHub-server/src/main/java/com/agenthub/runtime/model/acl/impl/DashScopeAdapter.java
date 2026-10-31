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
 * 阿里云灵积（DashScope）原生协议的模型适配器。所属域：runtime（模型出站 ACL）。
 * 与 OpenAI 兼容族的差异（本类负责消化，业务侧不可见）：
 * <ol>
 *   <li>路径：{@code POST {root}/services/aigc/text-generation/generation}，消息包在 {@code input.messages}，
 *       采样参数与 tools 包在 {@code parameters} 里——不是 OpenAI 的平铺结构；</li>
 *   <li>流式要额外带 {@code X-DashScope-SSE: enable} 请求头，且<b>没有 {@code [DONE]} 标记</b>，
 *       只能靠 {@code output.finish_reason} 或 HTTP EOF 收尾；</li>
 *   <li>要显式要求 {@code result_format=message}，否则返回的是 {@code output.text} 纯文本，拿不到工具调用；</li>
 *   <li>思考字段是 {@code message.reasoning_content}；工具调用分片不带 index，需按 id 归组。</li>
 * </ol>
 * 兼容模式：供应商 baseUrl 含 {@code compatible-mode} 时，其语义就是 OpenAI 兼容协议
 * （现网模型管理允许这样填），本类内部委派给 {@link OpenAICompatibleAdapter}，避免第二份重复实现。
 * 职责边界：本类只管「发什么请求 + 把字节流交给谁」；请求体映射在 {@link DashScopeRequestMapper}，
 * 响应映射在 {@link DashScopeResponseMapper}，流式状态机在 {@link DashScopeStreamDecoder}，
 * done 兜底在 {@link StreamAssembler}。
 * 约束：
 * 1. 厂商字段名（output / choices / tool_calls / finish_reason …）只允许出现在本类与上述三个映射类中；
 * 2. 不持有可变状态、可并发复用；凭证只在构造时从 {@link AdapterEndpointConfig} 取用；
 * 3. 不做重试与降级切换（ModelGateway 职责），也不做 Token Exchange。
 */
public class DashScopeAdapter implements ModelProviderAdapter {

    /** 原生文本生成接口固定挂在 /api/v1 下 */
    private static final String NATIVE_ROOT_SUFFIX = "/api/v1";
    /** 原生文本生成服务路径 */
    private static final String GENERATION_PATH = "/services/aigc/text-generation/generation";
    /** 灵积要求流式请求显式开启 SSE 的请求头名 */
    private static final String SSE_ENABLE_HEADER = "X-DashScope-SSE";
    /** 默认供应商标识，仅在配置未指定时兜底 */
    private static final String DEFAULT_PROVIDER = "dashscope";
    /** 兼容模式 baseUrl 的特征片段 */
    private static final String COMPATIBLE_MODE_HINT = "compatible-mode";

    private final AdapterEndpointConfig config;
    private final WebClient webClient;
    private final ObjectMapper json;
    /** 请求体映射器，无状态可复用 */
    private final DashScopeRequestMapper requestMapper;
    /** 非流式响应映射器，无状态可复用 */
    private final DashScopeResponseMapper responseMapper;
    /** 兼容模式的委派目标；原生模式为 null（构造时定，之后不再变，故可并发共享） */
    private final OpenAICompatibleAdapter compatibleDelegate;

    /**
     * @param config    连接与凭证配置，一次模型实例一份
     * @param webClient 已配置 baseUrl 的客户端；由调用方注入便于单测替换
     * @param json      序列化器，复用容器配置
     */
    public DashScopeAdapter(AdapterEndpointConfig config, WebClient webClient, ObjectMapper json) {
        this.config = config;
        this.webClient = webClient;
        this.json = json;
        this.requestMapper = new DashScopeRequestMapper(json);
        this.responseMapper = new DashScopeResponseMapper(json);
        this.compatibleDelegate = config.getBaseUrl().contains(COMPATIBLE_MODE_HINT)
                ? buildCompatibleDelegate(config, webClient, json)
                : null;
    }

    /**
     * 兼容模式委派：把同一份端点配置翻译成 OpenAI 兼容配置。
     * baseUrl 已含 /compatible-mode/v1，故对话路径固定为 /chat/completions。
     *
     * @param config    灵积端点配置
     * @param webClient 复用同一客户端，保证超时与连接池口径一致
     * @param json      序列化器
     * @return OpenAI 兼容适配器
     */
    private static OpenAICompatibleAdapter buildCompatibleDelegate(AdapterEndpointConfig config,
                                                                   WebClient webClient, ObjectMapper json) {
        OpenAICompatibleConfig compatible = OpenAICompatibleConfig.builder()
                .providerName(config.getProviderName() == null ? DEFAULT_PROVIDER : config.getProviderName())
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .requestTimeout(config.getRequestTimeout())
                .streamIdleTimeout(config.getStreamIdleTimeout())
                .build();
        return new OpenAICompatibleAdapter(compatible, webClient, json);
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
     * 流式对话。原生模式：请求映射 → SSE 逐帧解码 → 收尾补 done（{@link StreamAssembler} 统一承担）。
     * 原生接口没有 [DONE]，故「上游 EOF 但无 finish_reason」是常态分支，由装配器按已观察到的状态收尾。
     *
     * @param request 统一请求
     * @return 事件流；正常与异常路径末尾都有且仅有一个 type=done
     */
    @Override
    public Flux<UnifiedStreamEvent> chatStream(UnifiedChatRequest request) {
        if (compatibleDelegate != null) {
            return compatibleDelegate.chatStream(request);
        }
        Flux<DataBuffer> body = webClient.post()
                .uri(generationUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                // 不开这个头，灵积会把整段结果一次性返回（非 SSE），前端就看不到增量
                .header(SSE_ENABLE_HEADER, "enable")
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

        return StreamAssembler.assemble(body, () -> new DashScopeStreamDecoder(json));
    }

    /**
     * 非流式对话。阻塞语义，调用方需在弹性调度器上执行。
     *
     * @param request 统一请求
     * @return 统一响应
     */
    @Override
    public UnifiedChatResponse chat(UnifiedChatRequest request) {
        if (compatibleDelegate != null) {
            return compatibleDelegate.chat(request);
        }
        return webClient.post()
                .uri(generationUri())
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
     * 拼原生接口地址。用户可能只填域名（https://dashscope.aliyuncs.com），
     * 也可能填到 /api/v1，两种都要能拼对，且不重复叠加版本段。
     *
     * @return 完整请求地址
     */
    private String generationUri() {
        String base = config.getBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        int index = base.indexOf(NATIVE_ROOT_SUFFIX);
        String root = index >= 0 ? base.substring(0, index) + NATIVE_ROOT_SUFFIX : base + NATIVE_ROOT_SUFFIX;
        return root + GENERATION_PATH;
    }
}

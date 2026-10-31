package com.agenthub.runtime.model.acl.impl;

import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.runtime.model.acl.ModelProviderAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 按供应商协议选择模型 Adapter 的工厂。所属域：runtime（模型出站 ACL）。
 * 为什么需要它：调用方（下期的 ModelGateway、正式会话装配处）只拿到「哪家协议 + 哪个端点」，
 * 不应自己 new 具体 Adapter——否则每加一家厂商都要改业务。这里集中维护「协议 → 构造方式」映射。
 * 与现网「新建供应商」协议字段对齐：{@link ModelProtocol} 的入库值就是选型键，
 * 因此供应商表里的 protocol 字符串可以直接驱动路由，无需额外映射表。
 * 约定：
 * 1. {@code CUSTOM} 本期不实现（REQ 明确本刀不做自定义万能解析），请求时抛明确异常而不是静默降级；
 * 2. {@code DASHSCOPE} 的 baseUrl 含 compatible-mode 时由 {@link DashScopeAdapter} 内部委派给
 *    OpenAI 兼容实现，工厂无需感知这层差异；
 * 3. 无状态、线程安全：WebClient 按每次调用新建（连接池由 reactor-netty 全局复用），
 *    不做实例缓存，避免不同租户 Key/超时互相污染。
 */
public class ModelAdapterFactory {

    /** 建连超时兜底值：端点配置未给时使用，避免连接不上时线程长期占用 */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 序列化器，全局共享（Jackson ObjectMapper 线程安全） */
    private final ObjectMapper json;
    /**
     * 协议 → 构造器。用 EnumMap 而不是 if-else 链：新增协议必须显式登记，
     * 编译期就能发现遗漏（switch 也够，但这里要传三个参数，函数式登记更简洁）。
     */
    private final Map<ModelProtocol, Function<AdapterEndpointConfig, ModelProviderAdapter>> builders =
            new EnumMap<>(ModelProtocol.class);

    /**
     * @param json 序列化器，复用容器配置
     */
    public ModelAdapterFactory(ObjectMapper json) {
        this.json = json;
        builders.put(ModelProtocol.OPENAI_COMPAT, config -> new OpenAICompatibleAdapter(
                toOpenAiConfig(config), buildWebClient(config), json));
        builders.put(ModelProtocol.DASHSCOPE, config -> new DashScopeAdapter(config, buildWebClient(config), json));
        builders.put(ModelProtocol.ANTHROPIC, config -> new AnthropicAdapter(config, buildWebClient(config), json));
    }

    /**
     * 按协议创建 Adapter。
     *
     * @param protocol 供应商协议（与 model_provider.protocol 入库值一致）
     * @param config   端点与凭证配置；apiKey 为调用边界解密后的明文
     * @return 对应 Adapter；调用方可直接 chat / chatStream
     * @throws IllegalArgumentException 协议为 null 或本期未实现（如 CUSTOM）
     */
    public ModelProviderAdapter create(ModelProtocol protocol, AdapterEndpointConfig config) {
        if (protocol == null) {
            throw new IllegalArgumentException("供应商协议未指定");
        }
        Function<AdapterEndpointConfig, ModelProviderAdapter> builder = builders.get(protocol);
        if (builder == null) {
            // 明确告知「本期没做」而不是退化成 OpenAI 兼容：静默降级会把错误推迟到运行期更难查
            throw new IllegalArgumentException("协议 " + protocol.name() + " 本期未实现模型 Adapter");
        }
        return builder.apply(config);
    }

    /**
     * 判断协议是否已有可用 Adapter。调用方（如试跑的模型装配）用它决定「放行」还是「明确拒绝」，
     * 而不是各自维护一份白名单——白名单一旦与工厂注册表脱节，就会出现「工厂支持但试跑报不支持」的错配。
     *
     * @param protocol 供应商协议，可空
     * @return true 表示 {@link #create} 能成功返回 Adapter
     */
    public boolean supports(ModelProtocol protocol) {
        return protocol != null && builders.containsKey(protocol);
    }

    /**
     * @return 当前已注册的协议集合（不可变快照），供错误文案与自检列出「可用协议」
     */
    public Set<ModelProtocol> supportedProtocols() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(builders.keySet()));
    }

    /**
     * 按协议字符串创建 Adapter，供直接持有库里 protocol 文本的调用方使用。
     *
     * @param protocol 协议入库值，大小写不敏感；空值或未知值都会抛异常
     * @param config   端点与凭证配置
     * @return 对应 Adapter
     * @throws IllegalArgumentException 协议为空、未知或本期未实现
     */
    public ModelProviderAdapter create(String protocol, AdapterEndpointConfig config) {
        if (protocol == null || protocol.isBlank()) {
            throw new IllegalArgumentException("供应商协议未指定");
        }
        ModelProtocol parsed;
        try {
            parsed = ModelProtocol.valueOf(protocol.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的供应商协议 " + protocol, ex);
        }
        return create(parsed, config);
    }

    /**
     * 端点配置 → OpenAI 兼容配置。两者字段高度重合，仅是默认值语义不同，
     * 这里显式搬运而不是让 OpenAI 兼容族依赖通用配置类。
     *
     * @param config 通用端点配置
     * @return OpenAI 兼容配置
     */
    private static OpenAICompatibleConfig toOpenAiConfig(AdapterEndpointConfig config) {
        return OpenAICompatibleConfig.builder()
                .providerName(config.getProviderName())
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .connectTimeout(config.getConnectTimeout())
                .requestTimeout(config.getRequestTimeout())
                .streamIdleTimeout(config.getStreamIdleTimeout())
                .build();
    }

    /**
     * 构造带建连超时的 WebClient。不设 connectTimeout 时，DNS 卡住或端口不通会一直挂着，
     * 前端只能干等；这里统一兜住。
     *
     * @param config 端点配置，读取超时设置
     * @return 客户端实例
     */
    private WebClient buildWebClient(AdapterEndpointConfig config) {
        Duration connect = config.getConnectTimeout() == null ? DEFAULT_CONNECT_TIMEOUT : config.getConnectTimeout();
        HttpClient httpClient = HttpClient.create()
                // 用 netty 的 connect 超时，而非 WebClient 层：后者对 SSE 长连接语义不可靠
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connect.toMillis());
        return WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}

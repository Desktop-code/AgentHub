package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.model.acl.ModelProviderAdapter;
import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;

/**
 * 把 ACL 的 {@link ModelProviderAdapter} 桥接成 AgentScope 的 {@link Model}。所属域：runtime 的 infra。
 * 为什么需要它：AgentScope 2.0.4 只自带 OpenAI 兼容与 DashScope 两个模型实现，<b>没有 Anthropic</b>。
 * 若试跑按「AgentScope 有没有对应 ChatModel」来放行协议，Anthropic 永远进不来。
 * 本类让已有 {@code AnthropicAdapter}（以及将来任何 Adapter）直接喂给 ReAct 引擎，
 * 于是「能否试跑」只取决于 {@code ModelAdapterFactory} 是否注册了该协议，不再另立白名单。
 * 边界：
 * 1. 本类只出现 Unified* 与 AgentScope 类型，<b>不出现任何厂商字段名</b>；
 *    厂商细节仍封闭在 {@code runtime.model.acl.impl} 内；
 * 2. 引擎要的是流式 chunk，因此固定走 {@code chatStream}；逐事件翻帧的规则在 {@link AcpEventMapper}，
 *    消息翻译在 {@link AcpRequestMapper}；
 * 3. 错误统一包成 {@link ModelException} 且不改写已脱敏的文案；
 * 4. 不采样用途的实例（嵌入 / 重排 / 语音 / 护栏）出站不得带 temperature，
 *    由 {@code allowTemperature} 开关在装配时一次性决定。
 * 线程：无状态可复用；每条订阅各自新建请求对象。
 */
final class AcpModelBridge implements Model {

    private final ModelProviderAdapter adapter;
    private final String modelName;
    private final boolean allowTemperature;
    private final AcpRequestMapper requests;
    private final AcpEventMapper events;

    /**
     * 向后兼容构造：默认允许带温度（试跑主链路是对话类用途）。
     *
     * @param adapter   ACL 适配器（三家之一），由 {@code ModelAdapterFactory} 产出
     * @param modelName 模型实例的 modelId，仅用于日志与引擎识别
     * @param json      序列化器，映射器共用
     */
    AcpModelBridge(ModelProviderAdapter adapter, String modelName, ObjectMapper json) {
        this(adapter, modelName, json, true);
    }

    /**
     * @param adapter          ACL 适配器（三家之一），由 {@code ModelAdapterFactory} 产出
     * @param modelName        模型实例的 modelId，仅用于日志与引擎识别
     * @param json             序列化器，映射器共用
     * @param allowTemperature false 表示该模型实例的用途不采样，出站省略 temperature
     */
    AcpModelBridge(ModelProviderAdapter adapter, String modelName, ObjectMapper json, boolean allowTemperature) {
        this.adapter = adapter;
        this.modelName = modelName;
        this.allowTemperature = allowTemperature;
        this.requests = new AcpRequestMapper(json);
        this.events = new AcpEventMapper(json);
    }

    /**
     * 引擎入口：引擎上下文 → 统一请求 → 统一事件流 → 引擎响应帧。
     *
     * @param messages 引擎上下文（system / user / assistant / tool 混合）
     * @param tools    工具 schema，可空
     * @param options  生成参数，可空
     * @return 逐帧响应流；错误已转成 {@link ModelException}
     */
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // 订阅时才建请求：chatStream 本身是冷流，提前构造请求不会发起调用但也没必要
        return Flux.defer(() -> {
            Instant start = Instant.now();
            UnifiedChatRequest request = requests.toRequest(messages, tools, options, allowTemperature);
            return adapter.chatStream(request)
                    // 引擎按到达顺序累积，flatMap 会打乱文本顺序，必须 concatMap
                    .concatMap(event -> Flux.fromIterable(events.toFrames(event, start)))
                    // 阻塞语义的客户端挪到弹性调度器，避免占用引擎的事件循环线程
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(AcpModelBridge::toModelException);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * {@inheritDoc}
     * 统一层不声明 structured output 能力，让引擎走「假 generate_response 工具」的兼容路径。
     */
    @Override
    public boolean supportsNativeStructuredOutput() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return false;
    }

    /**
     * {@inheritDoc}
     * 返回 0 表示「不知道」，引擎不会据此触发上下文压缩，行为与未配置时一致。
     */
    @Override
    public int getContextWindowSize() {
        return 0;
    }

    /**
     * 把内部异常包成引擎认识的 {@link ModelException}；已是引擎异常时原样返回。
     *
     * @param error 原始异常，可空
     * @return 引擎异常
     */
    private static Throwable toModelException(Throwable error) {
        if (error instanceof ModelException) {
            return error;
        }
        String message = error == null || error.getMessage() == null || error.getMessage().isBlank()
                ? "模型调用失败" : error.getMessage();
        return new ModelException(message, error);
    }
}

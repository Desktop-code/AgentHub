package com.agenthub.runtime.model.acl;

import reactor.core.publisher.Flux;

/**
 * 模型供应商适配器契约。所属域：runtime（模型出站 ACL）。
 * 调用方：下期的 ModelGateway 实现（网关按 providerType + 协议族选一个 Adapter）以及 Gateway 边界上的
 * Trace / 计费埋点；实现方：每家厂商一个实现（同族 OpenAI 兼容可共用一个 + 差异插件）。
 * 实现约束：
 * 1. 只在本类实现内部出现厂商 SDK 的请求/响应类型，方法签名与异常一律不得泄漏厂商字段；
 * 2. chatStream 返回的 Flux 必须是冷流（每次订阅才发起真实请求），并由实现保证最后一定有一个
 *    type=DONE 的事件；上游缺结束标识、读超时、客户端取消都由实现侧兜底补发；
 * 3. 阻塞式 HTTP 客户端必须切到弹性调度器（如 subscribeOn(Schedulers.boundedElastic())），
 *    禁止在读线程上做阻塞 IO；
 * 4. 实现无状态、可并发复用，凭证由实现按请求上下文自行取用，禁止缓存在适配器实例字段里。
 * 本期只有骨架，没有任何实现类。
 */
public interface ModelProviderAdapter {

    /**
     * 供应商标识，用于网关选型与日志摘要（如 deepseek / glm / anthropic）。
     * 必须稳定、小写、不含版本号。
     *
     * @return 供应商名
     */
    String getProviderName();

    /**
     * 非流式对话。一次性拿到完整结果，供不需要逐字输出的场景（Router 判定、结构化抽取）使用。
     *
     * @param request 统一请求；messages 不得为空
     * @return 统一响应；finishReason 不允许为 null，上游缺失时映射为 ERROR
     */
    UnifiedChatResponse chat(UnifiedChatRequest request);

    /**
     * 流式对话。事件顺序约定：文本/思考/工具调用增量在前，可穿插 usage，最后必须是 done。
     *
     * @param request 统一请求；stream 字段由实现忽略（本方法即流式语义）
     * @return 事件流；调用方取消订阅等同于客户端取消，实现需释放上游连接
     */
    Flux<UnifiedStreamEvent> chatStream(UnifiedChatRequest request);
}

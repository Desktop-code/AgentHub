package com.agenthub.runtime.model.acl;

import reactor.core.publisher.Flux;

/**
 * 模型出站统一入口（本期空壳，无实现）。所属域：runtime（模型出站 ACL）。
 * 调用方：下期的 ReAct 执行器与正式会话编排；实现方：下期的网关实现，负责按「供应商类型 + 协议族」
 * 选择 ModelProviderAdapter，并在边界上做 Trace 埋点、token 记账与 done 兜底。
 * 与 AgentScope 的关系：一期不替换 AgentScope，先让新代码只依赖本接口；
 * 待 Gateway 实现落地后再逐步把对模型的直接调用收口到此处。
 * 本期明确不做：不写实现、不改试跑链路（TrialRunAppService / ReActTrialExecutor 等均未改动）。
 */
public interface ModelGateway {

    /**
     * 非流式统一请求。实现需保证：解析出目标模型与凭证后委派对应 Adapter，异常转换为统一业务异常，
     * 不向上抛厂商 SDK 异常类型。
     *
     * @param request 统一请求
     * @return 统一响应
     */
    UnifiedChatResponse chat(UnifiedChatRequest request);

    /**
     * 流式统一请求。实现需保证：无论上游正常结束、截断、超时还是客户端取消，流末尾都有且仅有一个 done。
     *
     * @param request 统一请求
     * @return 统一事件流
     */
    Flux<UnifiedStreamEvent> chatStream(UnifiedChatRequest request);
}

/**
 * 模型防腐层的 Adapter 实现。所属域：runtime（模型出站 ACL）。
 * 约定：
 * 1. 厂商字段名（choices / delta / tool_calls / finish_reason / content blocks / stop_reason …）
 *    只允许出现在本包内，对外只产出 {@code com.agenthub.runtime.model.acl} 下的 Unified* 类型；
 * 2. 不引入任何厂商 SDK，HTTP 出站只用 Spring WebClient + Reactor；
 * 3. 已实现三族协议，由 {@link com.agenthub.runtime.model.acl.impl.ModelAdapterFactory} 按
 *    {@code ModelProtocol} 路由（与「新建供应商」的协议字段同一套枚举）：
 *    <ul>
 *      <li>{@code OPENAI_COMPAT} → {@link com.agenthub.runtime.model.acl.impl.OpenAICompatibleAdapter}
 *          （DeepSeek / GLM / Ollama / vLLM / 多数中转，也承接灵积 compatible-mode）；</li>
 *      <li>{@code DASHSCOPE} → {@link com.agenthub.runtime.model.acl.impl.DashScopeAdapter}
 *          （原生 generation 接口；baseUrl 含 compatible-mode 时内部委派给 OpenAI 兼容实现）；</li>
 *      <li>{@code ANTHROPIC} → {@link com.agenthub.runtime.model.acl.impl.AnthropicAdapter}
 *          （Messages 协议，含 thinking block 与 tool_use 的 input_json_delta）。</li>
 *    </ul>
 *    {@code CUSTOM} 本期不做，工厂会明确报错而不是静默降级。
 * 4. 三家共用同一套流式骨架，新增协议只需实现 {@link com.agenthub.runtime.model.acl.impl.VendorStreamDecoder}：
 *    SSE 切帧（SsePayloads）、done 兜底（StreamAssembler）、错误脱敏（UpstreamFailures）都已统一，
 *    不要在 Adapter 里另写一套，否则「五种收尾路径」的一致性会被打破。
 */
package com.agenthub.runtime.model.acl.impl;

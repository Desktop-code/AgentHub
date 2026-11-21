package com.agenthub.spi.llm;

/**
 * 平台内部「一次性文本生成」出站口。所属域：spi。
 * <p>
 * 存在的理由：查询改写、摘要这类**非对话**生成需要复用同一套能力（解密 Key、协议×用途矩阵、
 * 计量、Trace、推理兜底），但调用方分别落在 rag / memory 等域。
 * 若让 rag 直接依赖 memory 域的 {@code MemoryModelClient}，域边界会被打通，
 * 因此这里只声明「系统段 + 正文 → 文本」这一件事，实现放在 runtime 的 infra。
 * <p>
 * <b>边界</b>：
 * <ul>
 *   <li>实现方必须按实例用途出站（REWRITE 就是 REWRITE），禁止偷偷改打 CHAT 探活或 CHAT 模型；</li>
 *   <li>实现方负责计量与 Trace（渠道仍为控制台），调用方不必自己记账；</li>
 *   <li>不得把 apiKey / Authorization 写进日志，异常文案也不得带密钥；</li>
 *   <li>出站失败抛业务异常，由调用方决定是否降级（如改写失败回退原问句）。</li>
 * </ul>
 * 线程：阻塞调用，禁止在首字路径或主请求线程上直接使用；RAG 只在工具线程内同步调用。
 */
public interface TextCompletionClient {

    /**
     * 用已解析的模型实例做一次文本生成。
     * <p>
     * 系统段永远是平台侧正文（锁定契约或提示词模板），场景说明只能追加在其后；
     * 待处理正文（如用户原问）作为用户消息传入。
     *
     * @param agentId           当前 Agent，计量用
     * @param sessionId         运行会话，可空（空则不计会话维）
     * @param operatorId        平台用户，计量用
     * @param modelInstanceId   已解析的模型实例 id，用途须与调用意图一致
     * @param systemInstruction 系统段（平台模板正文）
     * @param scenarioExtra     追加区，可空；只追加不替换
     * @param source            待处理正文，不得含密钥
     * @param temperature       采样温度，可空表示用实例默认
     * @param maxTokens         输出上限，可空表示用实例默认
     * @return 模型正文；可能为空白，调用方需自行判空
     * @throws com.agenthub.common.error.BizException 出站失败或无可用正文
     */
    String complete(long agentId, Long sessionId, long operatorId, long modelInstanceId,
                    String systemInstruction, String scenarioExtra, String source,
                    Double temperature, Integer maxTokens);
}

package com.agenthub.common.text;

/**
 * 出站失败响应体的脱敏摘要。所属域：common（横切工具，不含业务判断）。
 *
 * <p>用途：模型出站（向量化 / 重排 / 探活）拿到非 2xx 响应时，把响应体折成单行、抹掉本次使用的
 * 明文 Key，并截到固定上限，放进 error_msg 或日志。之所以要单列一类：这段逻辑原先在
 * {@code ModelCenterEmbeddingClient}、{@code ModelCenterRerankClient}、{@code ModelHttpSupport}
 * 里逐字复制了三份，而它同时承载「不泄 Key」的安全职责——漏改一处就等于漏一道闸。
 *
 * <p>边界（重要）：
 * <ul>
 *   <li>只抹**本次出站的这把 Key**（子串替换），不做通用密钥正则；通用脱敏另有 {@code TrialTexts#redact}
 *       与 {@code UpstreamBodyBrief}，语义不同，禁止互相替换；</li>
 *   <li>不做 JSON 结构解析、不挑 {@code error.message}——那是 {@code UpstreamBodyBrief} 的职责；</li>
 *   <li>空响应体返回空串，调用方据此不再拼冒号。</li>
 * </ul>
 * 线程安全：无状态，静态纯函数。
 */
public final class SecretBrief {

    /** 摘要上限：够看清原因，又不至于把整段响应体写进 error_msg */
    public static final int MAX_LENGTH = 180;

    /** 工具类禁止实例化。 */
    private SecretBrief() {
    }

    /**
     * 折成单行、抹掉本次 Key、截到 {@link #MAX_LENGTH}。
     *
     * @param body   响应体原文，可空
     * @param apiKey 本次出站的明文 Key；空则只折行与截断
     * @return 不含该 Key 的单行摘要；空体返回空串
     */
    public static String brief(String body, String apiKey) {
        if (body == null || body.isBlank()) {
            return "";
        }
        // 先折行再抹 Key：折行不影响 Key 的连续子串，但能让「HTTP 500 xxx」保持单行
        String text = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (apiKey != null && !apiKey.isBlank()) {
            text = text.replace(apiKey, "***");
        }
        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
    }
}

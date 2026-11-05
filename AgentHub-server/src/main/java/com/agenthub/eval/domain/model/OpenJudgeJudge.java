package com.agenthub.eval.domain.model;

/**
 * 本 run 打分模型的出站凭据。所属域：eval 的 domain。
 * <p>由 {@code JudgeModelResolver} 从模型中心按 instanceId 解出，只用于
 * <b>Java → 本机环回 sidecar</b> 的一次性评分请求：
 * 禁止写应用日志、禁止落 {@code eval_result.openjudge_raw_json} / {@code scores_json} / 报告。
 * <p>{@link #toString()} 已脱敏：即使对象被误打进日志，也只会出现 {@code ***} 而不是明文 Key。
 * <p>不可变、无状态，可跨线程传递（但不应长期持有）。
 */
public final class OpenJudgeJudge {

    /** Judge 模型名（模型中心的 modelId，如 qwen-plus） */
    private final String model;
    /**
     * OpenAI 兼容基址。空表示「不指定」：sidecar 回落自身环境变量 {@code OPENAI_BASE_URL}。
     */
    private final String baseUrl;
    /** 明文 API Key；只在请求体里出现，永不入库、永不进日志 */
    private final String apiKey;

    /**
     * @param model   Judge 模型名；必填
     * @param baseUrl OpenAI 兼容基址；可空（空 = 交给 sidecar 的环境变量）
     * @param apiKey  明文 Key；必填
     */
    public OpenJudgeJudge(String model, String baseUrl, String apiKey) {
        this.model = model;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** @return Judge 模型名 */
    public String getModel() {
        return model;
    }

    /** @return OpenAI 兼容基址；可能为空 */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** @return 明文 API Key；调用方不得持久化或打日志 */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 脱敏展示：只留模型名与基址，Key 恒为 {@code ***}。
     *
     * @return 可安全写日志的字符串
     */
    @Override
    public String toString() {
        return "OpenJudgeJudge{model=" + model + ", baseUrl=" + baseUrl + ", apiKey=***}";
    }
}

package com.agenthub.runtime.model.acl.impl;

import java.time.Duration;

/**
 * OpenAI 兼容 Adapter 的连接与凭证配置。所属域：runtime（模型出站 ACL）。
 * 不可变：由调用边界（试跑/正式会话装配处）按具体模型实例新建，交给 Adapter 后不再修改。
 * 安全约束：apiKey 只以 final 字段持有，toString 刻意不输出它，禁止打日志或写进 Unified* 模型。
 * 生命周期：一次调用一份（模型实例切换就新建），不跨租户复用。
 */
public final class OpenAICompatibleConfig {

    /** 默认协议路径。绝大多数 OpenAI 兼容中转都用这个 */
    private static final String DEFAULT_CHAT_PATH = "/chat/completions";
    /** 默认供应商标识，供网关选型与日志摘要 */
    private static final String DEFAULT_PROVIDER_NAME = "openai-compatible";

    /** 供应商标识（deepseek / glm / ollama …），仅用于日志与网关选型 */
    private final String providerName;
    /** 供应商地址，含版本段，例如 https://api.deepseek.com/v1 */
    private final String baseUrl;
    /** 对话补全路径，默认 /chat/completions */
    private final String chatPath;
    /** 出站明文 Key；只存在于本对象与请求头里 */
    private final String apiKey;
    /** 模型实例的 modelId，例如 deepseek-chat */
    private final String modelName;
    /** 建连超时 */
    private final Duration connectTimeout;
    /** 非流式请求整体超时（阻塞等待上限） */
    private final Duration requestTimeout;
    /** 流式两帧之间的最大空闲时间，超时按 TIMEOUT 收尾并补 done */
    private final Duration streamIdleTimeout;

    private OpenAICompatibleConfig(Builder builder) {
        this.providerName = builder.providerName;
        this.baseUrl = requireText(builder.baseUrl, "baseUrl");
        this.chatPath = builder.chatPath;
        this.apiKey = requireText(builder.apiKey, "apiKey");
        this.modelName = requireText(builder.modelName, "modelName");
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
        this.streamIdleTimeout = builder.streamIdleTimeout;
    }

    /**
     * 校验必填文本，缺失时直接失败而不是等到发请求才暴露。
     *
     * @param value 待校验值
     * @param field 字段名，仅用于异常文案，不含真实值
     * @return 去首尾空白后的值
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OpenAI 兼容配置缺少 " + field);
        }
        return value.trim();
    }

    /**
     * @return 新建配置构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getProviderName() {
        return providerName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getChatPath() {
        return chatPath;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public Duration getStreamIdleTimeout() {
        return streamIdleTimeout;
    }

    /**
     * 日志用摘要。禁止把 apiKey 打进来，故这里显式拼装而不是用 IDE 生成的字段全量输出。
     *
     * @return 含供应商、模型与地址的摘要
     */
    @Override
    public String toString() {
        return "OpenAICompatibleConfig{provider=" + providerName + ", model=" + modelName + ", baseUrl=" + baseUrl + "}";
    }

    /**
     * 配置构建器。只暴露必要的可调项，其余给安全默认值。
     */
    public static final class Builder {

        private String providerName = DEFAULT_PROVIDER_NAME;
        private String baseUrl;
        private String chatPath = DEFAULT_CHAT_PATH;
        private String apiKey;
        private String modelName;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(120);
        private Duration streamIdleTimeout = Duration.ofSeconds(60);

        private Builder() {
        }

        public Builder providerName(String value) {
            this.providerName = (value == null || value.isBlank()) ? DEFAULT_PROVIDER_NAME : value.trim();
            return this;
        }

        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        public Builder chatPath(String value) {
            this.chatPath = (value == null || value.isBlank()) ? DEFAULT_CHAT_PATH : value.trim();
            return this;
        }

        /** @param value 解密后的明文 Key；调用方负责用完不缓存 */
        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

        public Builder modelName(String value) {
            this.modelName = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            if (value != null && !value.isNegative() && !value.isZero()) {
                this.connectTimeout = value;
            }
            return this;
        }

        public Builder requestTimeout(Duration value) {
            if (value != null && !value.isNegative() && !value.isZero()) {
                this.requestTimeout = value;
            }
            return this;
        }

        public Builder streamIdleTimeout(Duration value) {
            if (value != null && !value.isNegative() && !value.isZero()) {
                this.streamIdleTimeout = value;
            }
            return this;
        }

        /**
         * @return 不可变配置
         * @throws IllegalArgumentException 必填项缺失
         */
        public OpenAICompatibleConfig build() {
            return new OpenAICompatibleConfig(this);
        }
    }
}

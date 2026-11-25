package com.agenthub.runtime.model.acl.impl;

import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.runtime.model.acl.ModelProviderAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型 Adapter 工厂与协议路由单测。所属域：runtime（ACL 实现）。
 * 覆盖：三个已实现协议各自路由到正确 Adapter、未知/未实现协议明确失败（不静默降级）、
 * 协议字符串大小写兼容、凭证不出现在 toString 里。
 * 约束：只构造对象，不发任何请求，零网络。
 */
class ModelAdapterFactoryTest {

    private static final ModelAdapterFactory FACTORY = new ModelAdapterFactory(new com.fasterxml.jackson.databind.ObjectMapper());

    /**
     * 与现网「新建供应商」协议枚举对齐：OPENAI_COMPAT / DASHSCOPE / ANTHROPIC 都要能路由。
     */
    @Test
    @DisplayName("三个已实现协议分别路由到对应 Adapter")
    void routesEachSupportedProtocol() {
        assertInstanceOf(OpenAICompatibleAdapter.class,
                FACTORY.create(ModelProtocol.OPENAI_COMPAT, config("test", "https://api.deepseek.com/v1")),
                "OPENAI_COMPAT 应走 OpenAI 兼容族");
        assertInstanceOf(DashScopeAdapter.class,
                FACTORY.create(ModelProtocol.DASHSCOPE, config("test", "https://dashscope.aliyuncs.com")),
                "DASHSCOPE 原生模式应走灵积 Adapter");
        assertInstanceOf(AnthropicAdapter.class,
                FACTORY.create(ModelProtocol.ANTHROPIC, config("test", "https://api.anthropic.com")),
                "ANTHROPIC 应走 Anthropic Adapter");
    }

    /**
     * 灵积兼容模式：仍走 DashScopeAdapter（内部再委派），保证调用方无需判断 baseUrl。
     */
    @Test
    @DisplayName("DASHSCOPE 的 compatible-mode 仍由 DashScopeAdapter 承接")
    void dashScopeCompatibleModeStillRoutesToDashScopeAdapter() {
        ModelProviderAdapter adapter = FACTORY.create(ModelProtocol.DASHSCOPE,
                config("test", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertInstanceOf(DashScopeAdapter.class, adapter);
    }

    /**
     * CUSTOM 本期明确不实现：必须抛明确异常，不能静默退化成 OpenAI 兼容。
     * 静默降级会把配置错误推迟到运行期，排查成本高得多。
     */
    @Test
    @DisplayName("CUSTOM 协议未实现时抛出明确异常而非静默降级")
    void customProtocolFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FACTORY.create(ModelProtocol.CUSTOM, config("test", "https://example.com")));
        assertTrue(ex.getMessage().contains("CUSTOM"), "异常文案应指明是哪个协议未实现");
        assertTrue(ex.getMessage().contains("未实现"), "异常文案应说明本期未实现");
    }

    /**
     * 协议字符串入口：库里存的是文本，需大小写不敏感并拒绝未知值。
     */
    @Test
    @DisplayName("协议字符串入口大小写不敏感，未知值明确失败")
    void parsesProtocolTextCaseInsensitively() {
        assertInstanceOf(AnthropicAdapter.class, FACTORY.create("anthropic", config("test", "https://api.anthropic.com")));
        assertInstanceOf(DashScopeAdapter.class, FACTORY.create("DashScope", config("test", "https://dashscope.aliyuncs.com")));
        assertThrows(IllegalArgumentException.class, () -> FACTORY.create("gpt4-all", config("test", "https://x.com")));
        assertThrows(IllegalArgumentException.class, () -> FACTORY.create("  ", config("test", "https://x.com")));
        assertThrows(IllegalArgumentException.class, () -> FACTORY.create((String) null, config("test", "https://x.com")));
    }

    /**
     * 协议为 null 时不能返回默认 Adapter：那等于把错误配置打到别家域名上。
     */
    @Test
    @DisplayName("协议为 null 时拒绝创建")
    void rejectsNullProtocol() {
        assertThrows(IllegalArgumentException.class, () -> FACTORY.create((ModelProtocol) null, config("test", "https://x.com")));
    }

    /**
     * 安全底线：配置对象的 toString / 日志摘要不得包含明文 Key。
     */
    @Test
    @DisplayName("配置与 Adapter 摘要不含明文 Key")
    void toStringNeverLeaksApiKey() {
        AdapterEndpointConfig config = config("anthropic", "https://api.anthropic.com");
        assertFalse(config.toString().contains(FAKE_KEY), "配置摘要不得包含 Key");

        ModelProviderAdapter anthropic = FACTORY.create(ModelProtocol.ANTHROPIC, config);
        ModelProviderAdapter dashScope = FACTORY.create(ModelProtocol.DASHSCOPE,
                config("dashscope", "https://dashscope.aliyuncs.com"));
        assertFalse(String.valueOf(anthropic).contains(FAKE_KEY), "Adapter 摘要不得包含 Key");
        assertFalse(String.valueOf(dashScope).contains(FAKE_KEY), "Adapter 摘要不得包含 Key");
        // 供应商名原样透出配置值，用于日志与选型，必须稳定小写
        assertEquals("anthropic", anthropic.getProviderName());
        assertEquals("dashscope", dashScope.getProviderName());
    }

    /**
     * 必填项缺失时构造即失败，而不是等发请求才报错。
     */
    @Test
    @DisplayName("缺少 baseUrl / apiKey / modelName 时构造即失败")
    void requiresMandatoryFields() {
        assertThrows(IllegalArgumentException.class,
                () -> AdapterEndpointConfig.builder().apiKey("k").modelName("m").build());
        assertThrows(IllegalArgumentException.class,
                () -> AdapterEndpointConfig.builder().baseUrl("https://x.com").modelName("m").build());
        assertThrows(IllegalArgumentException.class,
                () -> AdapterEndpointConfig.builder().baseUrl("https://x.com").apiKey("k").build());
    }

    // ---------- 辅助 ----------

    /** 假 Key，仅用于断言不出现在摘要里 */
    private static final String FAKE_KEY = "sk-test-not-real";

    /**
     * @param protocol 供应商标识，用于断言 providerName 透出
     * @param baseUrl  供应商地址
     * @return 端点配置
     */
    private static AdapterEndpointConfig config(String protocol, String baseUrl) {
        return AdapterEndpointConfig.builder()
                .providerName(protocol)
                .baseUrl(baseUrl)
                .apiKey(FAKE_KEY)
                .modelName("test-model")
                .streamIdleTimeout(Duration.ofSeconds(5))
                .build();
    }
}

package com.agenthub.model.infra.adapter;

import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议探活关键格：已通成功、未适配不静默、视觉多模态报文、护栏端点。
 * 本文件只在本机执行（src/test 不入库），全部打本机 mock，不触达真实模型。
 */
class ModelClientProbeTest {

    private ModelHttpSupport http() {
        return new ModelHttpSupport(HttpClient.newHttpClient(), new ModelHttpProperties());
    }

    @Test
    void openAiChatAndGuardrailUseTheirOwnEndpoints() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/v1/chat/completions", 200, "{\"ok\":true}");
            server.respond("/v1/moderations", 200, "{\"results\":[]}");
            OpenAiCompatClient client = new OpenAiCompatClient(http());

            ProbeResult chat = client.probe(new ProbeCommand(server.baseUrl() + "/v1", "sk-test",
                    "gpt-4o-mini", ModelCapability.CHAT));
            assertTrue(chat.success(), chat.message());
            assertEquals("/v1/chat/completions", server.lastRequest().path());

            ProbeResult guardrail = client.probe(new ProbeCommand(server.baseUrl() + "/v1", "sk-test",
                    "omni-moderation-latest", ModelCapability.GUARDRAIL));
            assertTrue(guardrail.success(), guardrail.message());
            assertEquals("/v1/moderations", server.lastRequest().path());
            assertTrue(guardrail.message().contains("未接入运行时拦截"), guardrail.message());
        }
    }

    @Test
    void unsupportedCellDoesNotIssueAnyHttpRequest() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            OpenAiCompatClient client = new OpenAiCompatClient(http());
            ProbeResult result = client.probe(new ProbeCommand(server.baseUrl() + "/v1", "sk-test",
                    "gpt-4o-mini", ModelCapability.RERANK));
            assertFalse(result.success());
            assertTrue(result.message().contains("该协议不支持此用途"), result.message());
            assertEquals(0, server.requestCount(), "未适配格不得发任何 HTTP 请求");
        }
    }

    @Test
    void dashScopeRerankAlwaysUsesNativePath() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/api/v1/services/rerank/text-rerank/text-rerank", 200, "{\"output\":{\"results\":[]}}");
            DashScopeClient client = new DashScopeClient(http());
            // 即使 baseUrl 是 compatible-mode，Rerank 也必须回到原生 /api/v1
            ProbeResult result = client.probe(new ProbeCommand(server.baseUrl() + "/compatible-mode/v1",
                    "sk-test", "qwen3-rerank", ModelCapability.RERANK));
            assertTrue(result.success(), result.message());
            assertEquals("/api/v1/services/rerank/text-rerank/text-rerank", server.lastRequest().path());
        }
    }

    @Test
    void dashScopeNativeVisionUsesMultimodalContentBlocks() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/api/v1/services/aigc/multimodal-generation/generation", 200,
                    "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"text\":\"ok\"}]}}]}}");
            DashScopeClient client = new DashScopeClient(http());
            ProbeResult result = client.probe(new ProbeCommand(server.baseUrl() + "/api/v1",
                    "sk-test", "qwen-vl-plus", ModelCapability.VISION));
            assertTrue(result.success(), result.message());
            assertEquals("/api/v1/services/aigc/multimodal-generation/generation", server.lastRequest().path());
            String body = server.lastRequest().body();
            assertTrue(body.contains("\"image\""), body);
            assertTrue(body.contains("\"text\""), body);
            assertTrue(body.contains("data:image/png;base64,"), body);
        }
    }

    @Test
    void dashScopeCompatibleVisionUsesImageUrl() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/compatible-mode/v1/chat/completions", 200, "{\"choices\":[]}");
            DashScopeClient client = new DashScopeClient(http());
            ProbeResult result = client.probe(new ProbeCommand(server.baseUrl() + "/compatible-mode/v1",
                    "sk-test", "qwen-vl-plus", ModelCapability.VISION));
            assertTrue(result.success(), result.message());
            assertTrue(server.lastRequest().body().contains("image_url"), server.lastRequest().body());
        }
    }

    @Test
    void anthropicVisionSendsBase64ImageBlock() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/v1/messages", 200, "{\"content\":[]}");
            AnthropicClient client = new AnthropicClient(http());
            ProbeResult result = client.probe(new ProbeCommand(server.baseUrl(), "sk-ant-test",
                    "claude-sonnet-4-5", ModelCapability.VISION));
            assertTrue(result.success(), result.message());
            assertEquals("/v1/messages", server.lastRequest().path());
            String body = server.lastRequest().body();
            assertTrue(body.contains("\"type\":\"image\""), body);
            assertTrue(body.contains("\"media_type\":\"image/png\""), body);
            assertFalse(body.contains("data:image/png;base64,"), "Anthropic 图片必须是裸 base64，不能塞 data URL：" + body);
            assertTrue(body.contains(VisionProbePayload.PNG_BASE64), body);
        }
    }

    @Test
    void anthropicGuardrailUsesMessagesClassificationPrompt() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/v1/messages", 200, "{\"content\":[]}");
            AnthropicClient client = new AnthropicClient(http());
            ProbeResult result = client.probe(new ProbeCommand(server.baseUrl(), "sk-ant-test",
                    "claude-haiku-4-5", ModelCapability.GUARDRAIL));
            assertTrue(result.success(), result.message());
            assertEquals("/v1/messages", server.lastRequest().path());
            assertTrue(server.lastRequest().body().contains("内容安全分类器"), server.lastRequest().body());
            assertTrue(result.message().contains("未接入运行时拦截"), result.message());
        }
    }

    @Test
    void anthropicEmbeddingIsUnsupportedWithoutHttp() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            AnthropicClient client = new AnthropicClient(http());
            ProbeResult result = client.probe(new ProbeCommand(server.baseUrl(), "sk-ant-test",
                    "claude-sonnet-4-5", ModelCapability.EMBEDDING));
            assertFalse(result.success());
            assertTrue(result.message().contains("用途未适配：ANTHROPIC×EMBEDDING"), result.message());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void anthropicChatStillUsesMessages() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/v1/messages", 200, "{\"content\":[]}");
            AnthropicClient client = new AnthropicClient(http());
            ProbeResult result = client.probe(new ProbeCommand(server.baseUrl(), "sk-ant-test",
                    "claude-sonnet-4-5", ModelCapability.REWRITE));
            assertTrue(result.success(), result.message());
            assertNotNull(server.lastRequest());
            assertTrue(server.lastRequest().headers().containsKey("X-api-key")
                    || server.lastRequest().headers().containsKey("x-api-key"), "应带 Anthropic 鉴权头");
        }
    }
}

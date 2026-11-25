package com.agenthub.model.infra.adapter;

import com.agenthub.common.error.BizException;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.model.SpeechRequest;
import com.agenthub.model.domain.service.SpeechParams;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语音出站关键格：兼容口 TTS 回归、DashScope 原生 TTS（data / url 两种返回）、原生 ASR、
 * 未适配协议不静默、探活不因静音空文本误判、附加参数浅合并。
 * 本文件只在本机执行（src/test 不入库），全部打本机 mock。
 */
class SpeechClientTest {

    private SpeechClient client() {
        return new SpeechClient(new SpeechHttpSupport(HttpClient.newHttpClient(), new ModelHttpProperties()));
    }

    @Test
    void openAiCompatibleTtsKeepsAudioSpeechEndpoint() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respondBytes("/v1/audio/speech", 200, new byte[]{1, 2, 3}, "audio/mpeg");
            SpeechRequest request = new SpeechRequest(ModelProtocol.OPENAI_COMPAT,
                    server.baseUrl() + "/v1", "sk-test", "tts-1", ModelCapability.TTS, "alloy", "mp3", Map.of());
            byte[] audio = client().synthesize(request, "你好");
            assertArrayEquals(new byte[]{1, 2, 3}, audio);
            assertEquals("/v1/audio/speech", server.lastRequest().path());
            String body = server.lastRequest().body();
            assertTrue(body.contains("\"input\":\"你好\""), body);
            assertTrue(body.contains("\"response_format\":\"mp3\""), body);
        }
    }

    @Test
    void dashScopeCompatibleTtsStillUsesAudioSpeechEndpoint() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respondBytes("/compatible-mode/v1/audio/speech", 200, new byte[]{4, 5}, "audio/mpeg");
            SpeechRequest request = new SpeechRequest(ModelProtocol.DASHSCOPE,
                    server.baseUrl() + "/compatible-mode/v1", "sk-test", "qwen-tts",
                    ModelCapability.TTS, "alloy", "mp3", Map.of());
            byte[] audio = client().synthesize(request, "你好");
            assertArrayEquals(new byte[]{4, 5}, audio);
            assertEquals("/compatible-mode/v1/audio/speech", server.lastRequest().path());
        }
    }

    @Test
    void dashScopeNativeTtsUsesSpeechSynthesizerAndDecodesAudioData() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            // AQID = 0x01 0x02 0x03
            server.respond("/api/v1/services/audio/tts/SpeechSynthesizer", 200,
                    "{\"output\":{\"audio\":{\"data\":\"AQID\"}}}");
            SpeechRequest request = new SpeechRequest(ModelProtocol.DASHSCOPE,
                    server.baseUrl() + "/api/v1", "sk-test", "qwen-audio-3.1-tts-flash",
                    ModelCapability.TTS, "longanhuan_v3.1", "wav", Map.of());
            byte[] audio = client().synthesize(request, "你好");
            assertArrayEquals(new byte[]{1, 2, 3}, audio);
            assertEquals("/api/v1/services/audio/tts/SpeechSynthesizer", server.lastRequest().path());
            String body = server.lastRequest().body();
            assertTrue(body.contains("\"voice\":\"longanhuan_v3.1\""), body);
            assertTrue(body.contains("\"format\":\"wav\""), body);
            assertTrue(body.contains("\"sample_rate\":24000"), body);
        }
    }

    @Test
    void dashScopeNativeTtsDownloadsAudioUrlWhenDataIsEmpty() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/api/v1/services/audio/tts/SpeechSynthesizer", 200,
                    "{\"output\":{\"audio\":{\"data\":\"\",\"url\":\"" + server.baseUrl() + "/audio/output.wav\"}}}");
            server.respondBytes("/audio/output.wav", 200, new byte[]{9, 8, 7}, "audio/wav");
            SpeechRequest request = new SpeechRequest(ModelProtocol.DASHSCOPE,
                    server.baseUrl() + "/api/v1", "sk-test", "qwen-audio-3.1-tts-flash",
                    ModelCapability.TTS, null, "wav", Map.of());
            byte[] audio = client().synthesize(request, "你好");
            assertArrayEquals(new byte[]{9, 8, 7}, audio);
            assertEquals(2, server.requestCount());
            assertEquals("/audio/output.wav", server.lastRequest().path());
        }
    }

    @Test
    void dashScopeNativeTtsRejectsUnsupportedFormatInsteadOfSilentlyChangingIt() throws Exception {
        SpeechRequest request = new SpeechRequest(ModelProtocol.DASHSCOPE,
                "http://127.0.0.1:1/api/v1", "sk-test", "qwen-audio-3.1-tts-flash",
                ModelCapability.TTS, "longanhuan_v3.1", "aac", Map.of());
        BizException ex = assertThrows(BizException.class, () -> client().synthesize(request, "你好"));
        assertTrue(ex.getMessage().contains("不支持 aac 格式"), ex.getMessage());
    }

    @Test
    void dashScopeNativeAsrSendsDataUrlAndReadsNestedText() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/api/v1/services/aigc/multimodal-generation/generation", 200,
                    "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"text\":\"你好世界\"}]}}]}}");
            SpeechRequest request = new SpeechRequest(ModelProtocol.DASHSCOPE,
                    server.baseUrl() + "/api/v1", "sk-test", "qwen3-asr-flash",
                    ModelCapability.ASR, null, null, Map.of());
            String text = client().transcribe(request, new byte[]{1, 2, 3}, "voice.wav", "audio/wav");
            assertEquals("你好世界", text);
            assertEquals("/api/v1/services/aigc/multimodal-generation/generation", server.lastRequest().path());
            String body = server.lastRequest().body();
            assertTrue(body.contains("data:audio/wav;base64,"), body);
            assertTrue(body.contains("\"result_format\":\"message\""), body);
        }
    }

    @Test
    void nativeAsrProbeSucceedsEvenWhenSilenceReturnsEmptyText() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/api/v1/services/aigc/multimodal-generation/generation", 200,
                    "{\"output\":{\"choices\":[{\"message\":{\"content\":[]}}]}}");
            SpeechRequest request = new SpeechRequest(ModelProtocol.DASHSCOPE,
                    server.baseUrl() + "/api/v1", "sk-test", "qwen3-asr-flash",
                    ModelCapability.ASR, null, null, Map.of());
            ProbeResult result = client().probe(request);
            assertTrue(result.success(), result.message());
            assertTrue(result.message().contains("DashScope 原生"), result.message());
        }
    }

    @Test
    void anthropicSpeechIsUnsupportedAndSaysSo() {
        SpeechRequest request = new SpeechRequest(ModelProtocol.ANTHROPIC,
                "https://api.anthropic.com", "sk-ant-test", "claude-sonnet-4-5",
                ModelCapability.ASR, null, null, Map.of());
        BizException ex = assertThrows(BizException.class,
                () -> client().transcribe(request, new byte[]{1}, "voice.wav", "audio/wav"));
        assertTrue(ex.getMessage().contains("该协议不支持此用途"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ANTHROPIC×ASR"), ex.getMessage());
    }

    @Test
    void nativeAsrEmptyTextDoesNotPretendSuccessForRealCall() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/api/v1/services/aigc/multimodal-generation/generation", 200,
                    "{\"output\":{\"choices\":[{\"message\":{\"content\":[]}}]}}");
            SpeechRequest request = new SpeechRequest(ModelProtocol.DASHSCOPE,
                    server.baseUrl() + "/api/v1", "sk-test", "qwen3-asr-flash",
                    ModelCapability.ASR, null, null, Map.of());
            BizException ex = assertThrows(BizException.class,
                    () -> client().transcribe(request, new byte[]{1}, "voice.wav", "audio/wav"));
            assertTrue(ex.getMessage().contains("没有识别到有效语音"), ex.getMessage());
        }
    }

    @Test
    void compatibleAsrProbeDoesNotFailOnEmptyText() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respondBytes("/v1/audio/transcriptions", 200,
                    "{\"text\":\"\"}".getBytes(StandardCharsets.UTF_8), "application/json");
            SpeechRequest request = new SpeechRequest(ModelProtocol.OPENAI_COMPAT,
                    server.baseUrl() + "/v1", "sk-test", "whisper-1",
                    ModelCapability.ASR, null, null, Map.of());
            ProbeResult result = client().probe(request);
            assertTrue(result.success(), result.message());
            assertFalse(result.message().contains("DashScope"));
        }
    }

    /**
     * 兼容口：附加参数合并到请求体顶层，键名原样；voice 覆盖平台默认音色，
     * 系统键 model / input 不被覆盖。
     */
    @Test
    void compatibleTtsMergesExtraParamsAtBodyRoot() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respondBytes("/v1/audio/speech", 200, new byte[]{1}, "audio/mpeg");
            Map<String, Object> extra = new java.util.LinkedHashMap<>();
            extra.put("voice", "cherry");
            extra.put("speed", 1.1);
            extra.put("model", "hacked-model");
            extra.put("input", "被覆盖的文本");
            extra.put("api_key", "sk-should-not-appear");
            SpeechRequest request = new SpeechRequest(ModelProtocol.OPENAI_COMPAT,
                    server.baseUrl() + "/v1", "sk-test", "tts-1", ModelCapability.TTS, "alloy", "mp3", extra);
            client().synthesize(request, "你好");
            String body = server.lastRequest().body();
            assertTrue(body.contains("\"voice\":\"cherry\""), body);
            assertTrue(body.contains("\"speed\":1.1"), body);
            assertTrue(body.contains("\"model\":\"tts-1\""), body);
            assertTrue(body.contains("\"input\":\"你好\""), body);
            assertFalse(body.contains("hacked-model"), body);
            assertFalse(body.contains("被覆盖的文本"), body);
            assertFalse(body.contains("sk-should-not-appear"), body);
        }
    }

    /**
     * DashScope 原生：附加参数合并到 input，text 不被覆盖、model 保持在顶层。
     */
    @Test
    void nativeTtsMergesExtraParamsIntoInput() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/api/v1/services/audio/tts/SpeechSynthesizer", 200,
                    "{\"output\":{\"audio\":{\"data\":\"AQID\"}}}");
            Map<String, Object> extra = new java.util.LinkedHashMap<>();
            extra.put("voice", "longwan_v2");
            extra.put("instruction", "用温柔的语气");
            extra.put("text", "被覆盖的文本");
            extra.put("model", "hacked-model");
            SpeechRequest request = new SpeechRequest(ModelProtocol.DASHSCOPE,
                    server.baseUrl() + "/api/v1", "sk-test", "qwen-audio-3.1-tts-flash",
                    ModelCapability.TTS, null, "wav", extra);
            client().synthesize(request, "你好");
            String body = server.lastRequest().body();
            assertTrue(body.contains("\"voice\":\"longwan_v2\""), body);
            assertTrue(body.contains("\"instruction\":\"用温柔的语气\""), body);
            assertTrue(body.contains("\"text\":\"你好\""), body);
            assertTrue(body.contains("\"model\":\"qwen-audio-3.1-tts-flash\""), body);
            assertFalse(body.contains("被覆盖的文本"), body);
            assertFalse(body.contains("hacked-model"), body);
        }
    }

    /**
     * 不填附加参数时，报文与改造前完全一致（回归）。
     */
    @Test
    void emptyExtraParamsKeepOriginalNativeBody() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respond("/api/v1/services/audio/tts/SpeechSynthesizer", 200,
                    "{\"output\":{\"audio\":{\"data\":\"AQID\"}}}");
            SpeechRequest request = new SpeechRequest(ModelProtocol.DASHSCOPE,
                    server.baseUrl() + "/api/v1", "sk-test", "qwen-audio-3.1-tts-flash",
                    ModelCapability.TTS, null, "mp3", null);
            client().synthesize(request, "你好");
            String body = server.lastRequest().body();
            assertTrue(body.contains("\"voice\":\"longanhuan_v3.1\""), body);
            assertTrue(body.contains("\"format\":\"mp3\""), body);
            assertFalse(body.contains("instruction"), body);
        }
    }

    /**
     * ASR 即便带了附加参数也不合并：合成参数不得混进识别请求。
     */
    @Test
    void asrDoesNotMergeExtraParams() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer()) {
            server.respondBytes("/v1/audio/transcriptions", 200,
                    "{\"text\":\"你好\"}".getBytes(StandardCharsets.UTF_8), "application/json");
            SpeechRequest request = new SpeechRequest(ModelProtocol.OPENAI_COMPAT,
                    server.baseUrl() + "/v1", "sk-test", "whisper-1", ModelCapability.ASR,
                    null, null, Map.of("voice", "cherry"));
            client().transcribe(request, new byte[]{1}, "voice.wav", "audio/wav");
            assertFalse(server.lastRequest().body().contains("cherry"), server.lastRequest().body());
        }
    }

    /**
     * 从 extra_json 读附加参数：只取 speechParams 子对象，且过滤凭证 / 保留键。
     */
    @Test
    void parseKeepsOnlySpeechParamsAndDropsBlockedKeys() {
        String extraJson = "{\"temperature\":0,\"speechParams\":{\"voice\":\"longanhuan_v3.1\","
                + "\"api_key\":\"sk-x\",\"model\":\"hacked\"}}";
        Map<String, Object> parsed = SpeechParams.parse(extraJson);
        assertEquals(1, parsed.size());
        assertEquals("longanhuan_v3.1", parsed.get("voice"));
    }
}

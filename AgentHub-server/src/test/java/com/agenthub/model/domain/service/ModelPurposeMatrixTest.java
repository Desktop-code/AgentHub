package com.agenthub.model.domain.service;

import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议×用途矩阵回归：33 格逐格断言，防止「未适配」被静默改成 Chat 或 GET 可达。
 * 本文件只在本机执行（src/test 不入库）。
 */
class ModelPurposeMatrixTest {

    private static final Set<ModelProtocol> BUSINESS_PROTOCOLS =
            EnumSet.of(ModelProtocol.OPENAI_COMPAT, ModelProtocol.DASHSCOPE, ModelProtocol.ANTHROPIC);

    @Test
    void generationFamilyIsAdaptedOnAllThreeProtocols() {
        for (ModelCapability capability : EnumSet.of(ModelCapability.CHAT, ModelCapability.ROUTER,
                ModelCapability.REWRITE, ModelCapability.SUMMARY, ModelCapability.MEMORY)) {
            for (ModelProtocol protocol : BUSINESS_PROTOCOLS) {
                PurposeRoute route = ModelPurposeMatrix.resolve(protocol, capability);
                assertTrue(route.adapted(), protocol + "×" + capability + " 应已适配");
                assertEquals(PurposeIoKind.CHAT, route.kind(), protocol + "×" + capability + " 应走聊天形态");
            }
        }
    }

    @Test
    void embeddingMatrixMatchesProtocolsThatReallyHaveEndpoint() {
        assertAdapted(ModelProtocol.OPENAI_COMPAT, ModelCapability.EMBEDDING, PurposeIoKind.EMBEDDING);
        assertAdapted(ModelProtocol.DASHSCOPE, ModelCapability.EMBEDDING, PurposeIoKind.EMBEDDING);
        assertUnsupported(ModelProtocol.ANTHROPIC, ModelCapability.EMBEDDING);
    }

    @Test
    void rerankMatrixOnlyDashScopeIsAdapted() {
        assertAdapted(ModelProtocol.DASHSCOPE, ModelCapability.RERANK, PurposeIoKind.RERANK);
        assertUnsupported(ModelProtocol.OPENAI_COMPAT, ModelCapability.RERANK);
        assertUnsupported(ModelProtocol.ANTHROPIC, ModelCapability.RERANK);
    }

    @Test
    void visionIsAdaptedOnAllThreeProtocols() {
        for (ModelProtocol protocol : BUSINESS_PROTOCOLS) {
            assertAdapted(protocol, ModelCapability.VISION, PurposeIoKind.VISION);
        }
    }

    @Test
    void audioMatrixKeepsAnthropicUnsupported() {
        assertAdapted(ModelProtocol.OPENAI_COMPAT, ModelCapability.ASR, PurposeIoKind.SPEECH_TO_TEXT);
        assertAdapted(ModelProtocol.DASHSCOPE, ModelCapability.ASR, PurposeIoKind.SPEECH_TO_TEXT);
        assertUnsupported(ModelProtocol.ANTHROPIC, ModelCapability.ASR);

        assertAdapted(ModelProtocol.OPENAI_COMPAT, ModelCapability.TTS, PurposeIoKind.TEXT_TO_SPEECH);
        assertAdapted(ModelProtocol.DASHSCOPE, ModelCapability.TTS, PurposeIoKind.TEXT_TO_SPEECH);
        assertUnsupported(ModelProtocol.ANTHROPIC, ModelCapability.TTS);
    }

    @Test
    void guardrailIsProbeOnlyButAdaptedOnAllThreeProtocols() {
        for (ModelProtocol protocol : BUSINESS_PROTOCOLS) {
            PurposeRoute route = ModelPurposeMatrix.resolve(protocol, ModelCapability.GUARDRAIL);
            assertTrue(route.adapted(), protocol + " 护栏探活应已适配");
            assertEquals(PurposeIoKind.GUARDRAIL, route.kind());
            assertTrue(route.message().contains("未接入运行时拦截"),
                    "护栏文案必须说清只是探活，不冒充已上线内容审查：" + route.message());
        }
    }

    @Test
    void unsupportedMessageTellsUserProtocolDoesNotSupportPurpose() {
        PurposeRoute route = ModelPurposeMatrix.resolve(ModelProtocol.ANTHROPIC, ModelCapability.EMBEDDING);
        assertFalse(route.adapted());
        assertEquals(PurposeIoKind.UNSUPPORTED, route.kind());
        assertTrue(route.message().contains("用途未适配：ANTHROPIC×EMBEDDING"), route.message());
        assertTrue(route.message().contains("该协议不支持此用途"), route.message());
    }

    @Test
    void customProtocolNeverPretendsToSupportBusinessPurpose() {
        for (ModelCapability capability : ModelCapability.values()) {
            assertFalse(ModelPurposeMatrix.resolve(ModelProtocol.CUSTOM, capability).adapted(),
                    "CUSTOM×" + capability + " 不得当成已适配");
        }
    }

    private static void assertAdapted(ModelProtocol protocol, ModelCapability capability, PurposeIoKind kind) {
        PurposeRoute route = ModelPurposeMatrix.resolve(protocol, capability);
        assertTrue(route.adapted(), protocol + "×" + capability + " 应已适配");
        assertEquals(kind, route.kind(), protocol + "×" + capability + " 出站形态不符");
    }

    private static void assertUnsupported(ModelProtocol protocol, ModelCapability capability) {
        PurposeRoute route = ModelPurposeMatrix.resolve(protocol, capability);
        assertFalse(route.adapted(), protocol + "×" + capability + " 不应适配");
        assertEquals(PurposeIoKind.UNSUPPORTED, route.kind());
        assertTrue(route.message().contains("该协议不支持此用途"), route.message());
    }
}

package com.agenthub.model.domain.model;

import com.agenthub.model.application.ModelChecks;
import com.agenthub.model.domain.model.ModelCapability.ModelCapabilityGroup;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途定稿枚举、五组全码、旧码映射与 SUMMARY 对话探活形态。
 */
class ModelCapabilityTest {

    /** 与前端 CAPABILITY_GROUPS 扁平顺序一致，防止前后端选项漂移 */
    private static final List<String> FRONTEND_FLAT = List.of(
            "CHAT", "ROUTER", "REWRITE", "SUMMARY", "MEMORY",
            "EMBEDDING", "RERANK",
            "VISION",
            "ASR", "TTS",
            "GUARDRAIL");

    @Test
    void fullEnumAndFiveGroups() {
        assertEquals(11, ModelCapability.values().length);
        Map<ModelCapabilityGroup, List<ModelCapability>> byGroup = ModelCapability.byGroup();
        assertEquals(5, byGroup.size());
        assertEquals(List.of(ModelCapability.CHAT, ModelCapability.ROUTER, ModelCapability.REWRITE,
                        ModelCapability.SUMMARY, ModelCapability.MEMORY),
                byGroup.get(ModelCapabilityGroup.GENERATION));
        assertEquals(List.of(ModelCapability.EMBEDDING, ModelCapability.RERANK),
                byGroup.get(ModelCapabilityGroup.RETRIEVAL));
        assertEquals(List.of(ModelCapability.VISION), byGroup.get(ModelCapabilityGroup.MULTIMODAL));
        assertEquals(List.of(ModelCapability.ASR, ModelCapability.TTS), byGroup.get(ModelCapabilityGroup.AUDIO));
        assertEquals(List.of(ModelCapability.GUARDRAIL), byGroup.get(ModelCapabilityGroup.SAFETY));

        List<String> backendFlat = ModelCapability.allCanonical().stream().map(Enum::name).toList();
        assertEquals(FRONTEND_FLAT, backendFlat);
    }

    @Test
    void legacyAliasesMapToCanonical() {
        assertEquals(ModelCapability.CHAT, ModelCapability.parse("LLM"));
        assertEquals(ModelCapability.ROUTER, ModelCapability.parse("INTENT"));
        assertEquals(ModelCapability.EMBEDDING, ModelCapability.parse("EMBED"));
        assertEquals(ModelCapability.VISION, ModelCapability.parse("OCR"));
        assertEquals(ModelCapability.VISION, ModelCapability.parse("PDF"));
        assertEquals(ModelCapability.CHAT, ModelChecks.capability("LLM"));
        assertEquals("CHAT", ModelChecks.capability("LLM").name());
        assertThrows(IllegalArgumentException.class, () -> ModelCapability.parse("chat"));
        assertFalse(ModelCapability.tryParse("PDF_GONE").isPresent());
    }

    @Test
    void summaryRemainsChatProbeAndNotRewrite() {
        assertTrue(ModelCapability.SUMMARY.usesChat());
        assertTrue(ModelCapability.CHAT.usesChat());
        assertTrue(ModelCapability.ROUTER.usesChat());
        assertTrue(ModelCapability.REWRITE.usesChat());
        assertFalse(ModelCapability.EMBEDDING.usesChat());
        assertTrue(ModelCapability.EMBEDDING.usesEmbedding());
        assertTrue(ModelCapability.RERANK.usesRerank());
        assertFalse(ModelCapability.RERANK.usesChat());
        assertFalse(ModelCapability.CHAT.usesRerank());
        // 用途互不借用：枚举值彼此独立
        Set<ModelCapability> chatLike = EnumSet.of(
                ModelCapability.CHAT, ModelCapability.ROUTER, ModelCapability.REWRITE,
                ModelCapability.SUMMARY, ModelCapability.MEMORY);
        assertTrue(ModelCapability.MEMORY.usesChat());
        assertTrue(ModelCapability.MEMORY.allowsChatDegrade());
        assertTrue(ModelCapability.SUMMARY.allowsChatDegrade());
        assertFalse(ModelCapability.EMBEDDING.allowsChatDegrade());
        assertEquals(chatLike, EnumSet.copyOf(
                ModelCapability.allCanonical().stream().filter(ModelCapability::usesChat).collect(Collectors.toSet())));
        assertEquals(ModelCapability.SUMMARY, ModelCapability.parse("SUMMARY"));
    }

    @Test
    void pdfExitedEnum() {
        assertThrows(IllegalArgumentException.class, () -> ModelCapability.valueOf("PDF"));
        assertThrows(IllegalArgumentException.class, () -> ModelCapability.valueOf("LLM"));
        assertEquals(ModelCapability.VISION, ModelCapability.parse("PDF"));
    }

    @Test
    void storageCodesIncludeLegacyForFilter() {
        assertEquals(List.of("CHAT", "LLM"), ModelCapability.storageCodes(ModelCapability.CHAT));
        assertEquals(List.of("ROUTER", "INTENT"), ModelCapability.storageCodes(ModelCapability.ROUTER));
        assertEquals(List.of("EMBEDDING", "EMBED"), ModelCapability.storageCodes(ModelCapability.EMBEDDING));
        assertEquals(List.of("VISION"), ModelCapability.storageCodes(ModelCapability.VISION).stream().limit(1).toList());
        assertTrue(ModelCapability.storageCodes(ModelCapability.VISION).containsAll(List.of("VISION", "OCR", "PDF")));
        assertEquals(List.of("SUMMARY"), ModelCapability.storageCodes(ModelCapability.SUMMARY));
    }
}

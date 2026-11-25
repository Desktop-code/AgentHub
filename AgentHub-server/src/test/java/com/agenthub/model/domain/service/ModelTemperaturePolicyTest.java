package com.agenthub.model.domain.service;

import com.agenthub.model.domain.model.ModelCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用途 × 温度策略单测。所属域：model。
 * 钉死三件事：对话类默认 0.7、改写类默认 0.2、不采样用途恒 0 且不出站。
 * 这些数值同时被前端 {@code TEMPERATURE_PROFILES} 引用，改这里必须同步前端。
 */
class ModelTemperaturePolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 对话类：默认 0.7，建议区间 0.3～1.0。
     */
    @Test
    @DisplayName("CHAT / VISION 默认 0.7，建议区间 0.3～1.0")
    void conversationUsesPointSeven() {
        for (ModelCapability capability : new ModelCapability[]{ModelCapability.CHAT, ModelCapability.VISION}) {
            ModelTemperaturePolicy.Profile profile = ModelTemperaturePolicy.profileOf(capability);
            assertTrue(profile.sampling(), capability + " 应走采样");
            assertEquals(0.7, profile.defaultTemperature(), 1e-9, capability + " 默认温度应为 0.7");
            assertEquals(0.3, profile.suggestedMin(), 1e-9);
            assertEquals(1.0, profile.suggestedMax(), 1e-9);
        }
    }

    /**
     * 确定性类：改写 / 摘要 / 记忆 / 路由默认 0.2，建议区间 0.1～0.3。
     */
    @Test
    @DisplayName("REWRITE / SUMMARY / MEMORY / ROUTER 默认 0.2，建议区间 0.1～0.3")
    void deterministicUsesPointTwo() {
        for (ModelCapability capability : new ModelCapability[]{ModelCapability.REWRITE, ModelCapability.SUMMARY,
                ModelCapability.MEMORY, ModelCapability.ROUTER}) {
            ModelTemperaturePolicy.Profile profile = ModelTemperaturePolicy.profileOf(capability);
            assertTrue(profile.sampling(), capability + " 应走采样");
            assertEquals(0.2, profile.defaultTemperature(), 1e-9, capability + " 默认温度应为 0.2");
            assertEquals(0.1, profile.suggestedMin(), 1e-9);
            assertEquals(0.3, profile.suggestedMax(), 1e-9);
        }
    }

    /**
     * 不采样用途：EMBEDDING / RERANK / ASR / TTS / GUARDRAIL 全部锁 0 且不走采样。
     */
    @Test
    @DisplayName("不采样用途温度锁 0 且标记为不采样")
    void nonSamplingPurposesAreZero() {
        for (ModelCapability capability : new ModelCapability[]{ModelCapability.EMBEDDING, ModelCapability.RERANK,
                ModelCapability.ASR, ModelCapability.TTS, ModelCapability.GUARDRAIL}) {
            ModelTemperaturePolicy.Profile profile = ModelTemperaturePolicy.profileOf(capability);
            assertFalse(profile.sampling(), capability + " 不应走采样");
            assertTrue(profile.fixedZero(), capability + " 应锁定 0");
            assertEquals(0.0, profile.defaultTemperature(), 1e-9);
            assertFalse(ModelTemperaturePolicy.samplesTemperature(capability));
        }
    }

    /**
     * 新建：extraJson 为空时按用途补默认值，对话 0.7 / 改写 0.2。
     */
    @Test
    @DisplayName("新建缺省按用途补默认温度")
    void blankExtraJsonGetsPurposeDefault() throws Exception {
        String chat = ModelTemperaturePolicy.applyTemperature(null, ModelCapability.CHAT);
        assertEquals(0.7, temperatureOf(chat), 1e-9);

        String rewrite = ModelTemperaturePolicy.applyTemperature(null, ModelCapability.REWRITE);
        assertEquals(0.2, temperatureOf(rewrite), 1e-9);
    }

    /**
     * 编辑：用户已存温度必须保留，不得被静默覆盖成用途默认值。
     */
    @Test
    @DisplayName("已存温度保留，不静默覆盖")
    void keepsUserTemperature() throws Exception {
        String kept = ModelTemperaturePolicy.applyTemperature("{\"temperature\":0.9}", ModelCapability.REWRITE);
        assertEquals(0.9, temperatureOf(kept), 1e-9);

        // 超过 2.0 的脏数据夹紧到上界，不直接落库
        String clamped = ModelTemperaturePolicy.applyTemperature("{\"temperature\":9.9}", ModelCapability.CHAT);
        assertEquals(2.0, temperatureOf(clamped), 1e-9);
    }

    /**
     * 不采样用途：无论入参写几度，落库一律 0；其它扩展键（如 dimension）不能丢。
     */
    @Test
    @DisplayName("不采样用途强制 0 且保留其它扩展键")
    void nonSamplingForcedToZero() throws Exception {
        String applied = ModelTemperaturePolicy.applyTemperature(
                "{\"temperature\":1.2,\"dimension\":1024}", ModelCapability.EMBEDDING);
        JsonNode root = JSON.readTree(applied);
        assertEquals(0.0, root.get("temperature").asDouble(), 1e-9);
        assertEquals(1024, root.get("dimension").asInt());
    }

    /**
     * 坏 JSON 不应把异常抛到业务层（上层已校验过，这里只做兜底）。
     */
    @Test
    @DisplayName("坏 JSON 兜底为空对象再补默认值")
    void brokenJsonFallsBack() throws Exception {
        String applied = ModelTemperaturePolicy.applyTemperature("not-json", ModelCapability.CHAT);
        assertEquals(0.7, temperatureOf(applied), 1e-9);
    }

    /**
     * @param extraJson 策略产出的 JSON
     * @return 其中的 temperature
     */
    private double temperatureOf(String extraJson) throws Exception {
        return JSON.readTree(extraJson).get("temperature").asDouble();
    }
}

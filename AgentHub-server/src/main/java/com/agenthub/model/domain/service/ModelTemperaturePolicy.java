package com.agenthub.model.domain.service;

import com.agenthub.model.domain.model.ModelCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 用途 ↔ 采样温度策略。所属域：model。
 * <p>
 * 为什么单列一策：温度不是「所有用途一个默认值」的字段——
 * <ul>
 *   <li>对话 / 多模态对话需要发散（默认 0.7）；</li>
 *   <li>改写 / 摘要 / 记忆抽取 / 路由要求稳定可复现（默认 0.2）；</li>
 *   <li>嵌入 / 重排 / 语音 / 护栏根本不采样，温度恒为 0，出站也不得带 temperature
 *       （见 {@code AcpRequestMapper} 与各请求映射器）。</li>
 * </ul>
 * 前端 {@code types/model.ts} 的 {@code TEMPERATURE_PROFILES} 与本类必须同值；单测负责钉死。
 * 线程：纯静态无状态，可并发调用。
 */
public final class ModelTemperaturePolicy {

    /** 对话 / 多模态对话类默认温度 */
    public static final double DEFAULT_CONVERSATION = 0.7;
    /** 改写 / 摘要 / 记忆抽取 / 路由默认温度 */
    public static final double DEFAULT_DETERMINISTIC = 0.2;
    /** 不采样用途固定写入的温度 */
    public static final double FIXED_ZERO = 0.0;
    /** 采样温度允许的硬上下界（超出即夹紧，避免把 9.9 之类写进库） */
    public static final double HARD_MIN = 0.0;
    public static final double HARD_MAX = 2.0;

    private static final ObjectMapper JSON = new ObjectMapper();
    /** extraJson 里温度的键名；与前端一致 */
    private static final String TEMPERATURE_KEY = "temperature";

    private ModelTemperaturePolicy() {
    }

    /**
     * 单个用途的温度策略快照。
     *
     * @param sampling           是否走采样（false 表示温度锁定 0 且出站不传）
     * @param defaultTemperature 新建 / 切换用途时带入的默认值
     * @param suggestedMin       建议区间下界（仅提示，不做硬校验）
     * @param suggestedMax       建议区间上界（仅提示，不做硬校验）
     */
    public record Profile(boolean sampling, double defaultTemperature, double suggestedMin, double suggestedMax) {

        /**
         * @return 该用途是否不使用温度
         */
        public boolean fixedZero() {
            return !sampling;
        }
    }

    /**
     * 按用途给出温度策略。
     * VISION 视作「多模态对话类」，与 CHAT 同档；ASR / TTS / GUARDRAIL 明确不采样，锁 0。
     *
     * @param capability 规范用途；null 按对话处理（兼容历史空值）
     * @return 策略快照
     */
    public static Profile profileOf(ModelCapability capability) {
        if (capability == null) {
            return conversation();
        }
        return switch (capability) {
            case CHAT, VISION -> conversation();
            case ROUTER, REWRITE, SUMMARY, MEMORY -> deterministic();
            // 不采样用途：温度无意义，统一锁 0，前端控件禁用
            case EMBEDDING, RERANK, ASR, TTS, GUARDRAIL -> new Profile(false, FIXED_ZERO, FIXED_ZERO, FIXED_ZERO);
        };
    }

    /**
     * @param capability 用途，可空
     * @return true 表示该用途走采样、允许用户改温度
     */
    public static boolean samplesTemperature(ModelCapability capability) {
        return profileOf(capability).sampling();
    }

    /**
     * 把温度策略落到 extraJson：不采样用途强制 0；采样用途缺省补建议默认值，已有值夹紧到 0～2 并保留。
     * 其它扩展键（如 embedding 的 dimension）原样保留。
     *
     * @param extraJson  已经过格式校验的扩展 JSON，可空
     * @param capability 规范用途
     * @return 可直接入库的 JSON 文本
     */
    public static String applyTemperature(String extraJson, ModelCapability capability) {
        Profile profile = profileOf(capability);
        ObjectNode root = readObject(extraJson);
        if (profile.fixedZero()) {
            // 不采样用途：不管入参写了几度，库里一律 0，避免「界面禁用但库里是 1.2」的错位
            root.put(TEMPERATURE_KEY, FIXED_ZERO);
            return root.toString();
        }
        JsonNode current = root.get(TEMPERATURE_KEY);
        if (current == null || !current.isNumber()) {
            // 缺省才补默认值；已有数值走下面的夹紧分支，绝不静默改成默认值
            root.put(TEMPERATURE_KEY, profile.defaultTemperature());
            return root.toString();
        }
        double value = current.asDouble();
        double clamped = Math.min(HARD_MAX, Math.max(HARD_MIN, Math.round(value * 10) / 10.0));
        root.put(TEMPERATURE_KEY, clamped);
        return root.toString();
    }

    private static Profile conversation() {
        return new Profile(true, DEFAULT_CONVERSATION, 0.3, 1.0);
    }

    private static Profile deterministic() {
        return new Profile(true, DEFAULT_DETERMINISTIC, 0.1, 0.3);
    }

    /**
     * 读成 JSON 对象；空、坏值或非对象时用空对象兜底（调用方已先做格式校验）。
     *
     * @param extraJson 扩展 JSON 文本
     * @return 可写节点
     */
    private static ObjectNode readObject(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode parsed = JSON.readTree(extraJson);
            if (parsed instanceof ObjectNode object) {
                return object;
            }
        } catch (Exception ignored) {
            // 解析失败按空对象处理，不把坏 JSON 继续往库里写
        }
        return JSON.createObjectNode();
    }
}

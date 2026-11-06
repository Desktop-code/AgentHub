package com.agenthub.model.domain.model;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 模型用途（权威英文码）。所属域：model。
 * 分组只用于控制台下拉展示，不是库字段；读写均经 {@link #parse(String)}，旧码映射为新码后落库。
 * PDF 已退出枚举（解析走工具/服务）；历史 PDF 行由补丁迁 VISION。
 */
public enum ModelCapability {

    /** 对话 / 补全，探活走极小 chat */
    CHAT(ModelCapabilityGroup.GENERATION),
    /** Router 判定与意图分发，探活与 CHAT 相同；不得默默当 CHAT 用在摘要链 */
    ROUTER(ModelCapabilityGroup.GENERATION),
    /** 查询改写，探活与 CHAT 相同；不得当 SUMMARY / MEMORY */
    REWRITE(ModelCapabilityGroup.GENERATION),
    /** 滚动摘要 / 窗口压缩 only；不得兼作记忆抽取 */
    SUMMARY(ModelCapabilityGroup.GENERATION),
    /** LONG 偏好 / 显式「记住」等记忆写模型；不得默默借用 SUMMARY */
    MEMORY(ModelCapabilityGroup.GENERATION),
    /** 向量嵌入，探活走 embeddings */
    EMBEDDING(ModelCapabilityGroup.RETRIEVAL),
    /** 重排序；须走协议×用途矩阵（如 DASHSCOPE text-rerank），禁冒充 Chat */
    RERANK(ModelCapabilityGroup.RETRIEVAL),
    /** 视觉 / 版面理解（含原 OCR）；OPENAI_COMPAT 与 DashScope 兼容模式发 1x1 图片，其余仍「用途未适配」 */
    VISION(ModelCapabilityGroup.MULTIMODAL),
    /** 语音识别（整段音频 → 文字）；出站走 /audio/transcriptions，禁止降级 CHAT */
    ASR(ModelCapabilityGroup.AUDIO),
    /** 语音合成（整段文字 → 音频）；出站走 /audio/speech，禁止降级 CHAT */
    TTS(ModelCapabilityGroup.AUDIO),
    /** 安全护栏；可配，连通测明确「用途未适配」 */
    GUARDRAIL(ModelCapabilityGroup.SAFETY);

    /** 旧码 → 新码；入参与库内遗留值读写都认 */
    private static final Map<String, ModelCapability> LEGACY = legacyMap();

    private final ModelCapabilityGroup group;

    ModelCapability(ModelCapabilityGroup group) {
        this.group = group;
    }

    /**
     * @return 控制台分组（非库字段）
     */
    public ModelCapabilityGroup group() {
        return group;
    }

    /**
     * 对话补全类探活：CHAT / ROUTER / REWRITE / SUMMARY / MEMORY。
     * 互不默默借用业务语义，仅表示出站协议形态相同。
     *
     * @return 是否按 chat 探活
     */
    public boolean usesChat() {
        return this == CHAT || this == ROUTER || this == REWRITE || this == SUMMARY || this == MEMORY;
    }

    /**
     * 槽耗尽后是否允许再降到 CHAT 槽（EMBEDDING 等禁止）。
     *
     * @return ROUTER / REWRITE / SUMMARY / MEMORY 为 true
     */
    public boolean allowsChatDegrade() {
        return this == ROUTER || this == REWRITE || this == SUMMARY || this == MEMORY;
    }

    /**
     * 是否走 embeddings 探活 / 出站。
     *
     * @return 嵌入用途
     */
    public boolean usesEmbedding() {
        return this == EMBEDDING;
    }

    /**
     * 是否走重排探活 / 出站（须查 {@code ModelPurposeMatrix}，非各协议均已适配）。
     *
     * @return 重排用途
     */
    public boolean usesRerank() {
        return this == RERANK;
    }

    /**
     * 是否走视觉探活 / 出站。当前仅 OPENAI_COMPAT 有图文混排路径，
     * 具体适配与否仍以 {@code ModelPurposeMatrix} 为准。
     *
     * @return 视觉用途
     */
    public boolean usesVision() {
        return this == VISION;
    }

    /**
     * 是否走「整段音频 → 文字」的语音识别出站（multipart 上传）。
     *
     * @return 语音识别用途
     */
    public boolean usesSpeechToText() {
        return this == ASR;
    }

    /**
     * 是否走「整段文字 → 音频」的语音合成出站。
     *
     * @return 语音合成用途
     */
    public boolean usesTextToSpeech() {
        return this == TTS;
    }

    /**
     * 语音类用途的合并判断。对话页按这个口径决定「是否已具备语音能力」，
     * 避免 STT / TTS 各写一份判断后漂移。
     *
     * @return ASR 或 TTS
     */
    public boolean usesSpeech() {
        return usesSpeechToText() || usesTextToSpeech();
    }

    /**
     * 解析用途：新码直接命中；旧码 LLM/INTENT/EMBED/OCR/PDF 映射为新码。
     *
     * @param raw 用途字符串，允许首尾空白
     * @return 规范枚举
     * @throws IllegalArgumentException 空白或未知码
     */
    public static ModelCapability parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("用途不能为空");
        }
        String key = raw.trim();
        try {
            return ModelCapability.valueOf(key);
        } catch (IllegalArgumentException ignored) {
            // 继续走旧码表
        }
        ModelCapability mapped = LEGACY.get(key);
        if (mapped != null) {
            return mapped;
        }
        // 容错：仅大小写不同时仍拒绝小写新码以外的乱码；旧码表按原大写约定
        throw new IllegalArgumentException("不支持的用途: " + key);
    }

    /**
     * @param raw 用途，可空
     * @return 解析结果，空白为空
     */
    public static Optional<ModelCapability> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * 控制台与单测用的完整码表（定稿顺序）。
     *
     * @return 不可变顺序列表
     */
    public static List<ModelCapability> allCanonical() {
        return List.of(values());
    }

    /**
     * 列表筛选用的库内码：规范码 + 尚未洗库的旧码（如 CHAT 兼查 LLM）。
     *
     * @param capability 规范用途
     * @return 至少含规范名
     */
    public static List<String> storageCodes(ModelCapability capability) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(capability.name());
        for (Map.Entry<String, ModelCapability> entry : LEGACY.entrySet()) {
            if (entry.getValue() == capability) {
                keys.add(entry.getKey());
            }
        }
        return List.copyOf(keys);
    }

    /**
     * 按五组列出全部码，供前后端对齐校验。
     *
     * @return 组 → 组内码（定稿顺序）
     */
    public static Map<ModelCapabilityGroup, List<ModelCapability>> byGroup() {
        Map<ModelCapabilityGroup, List<ModelCapability>> map = new LinkedHashMap<>();
        for (ModelCapabilityGroup g : ModelCapabilityGroup.values()) {
            map.put(g, Arrays.stream(values()).filter(c -> c.group == g).toList());
        }
        return map;
    }

    private static Map<String, ModelCapability> legacyMap() {
        Map<String, ModelCapability> map = new LinkedHashMap<>();
        map.put("LLM", CHAT);
        map.put("INTENT", ROUTER);
        map.put("EMBED", EMBEDDING);
        map.put("OCR", VISION);
        // PDF 退出用途枚举：历史行与入参迁 VISION（视觉/版面理解）
        map.put("PDF", VISION);
        return Map.copyOf(map);
    }

    /**
     * 用途分组文案。仅展示用，不入库。
     */
    public enum ModelCapabilityGroup {
        /** 生成：对话、路由、改写、摘要、记忆抽取 */
        GENERATION("生成"),
        /** 检索：嵌入、重排 */
        RETRIEVAL("检索"),
        /** 多模态：视觉 */
        MULTIMODAL("多模态"),
        /** 音频：ASR、TTS */
        AUDIO("音频"),
        /** 安全：护栏 */
        SAFETY("安全");

        private final String labelZh;

        ModelCapabilityGroup(String labelZh) {
            this.labelZh = labelZh;
        }

        /**
         * @return 中文组名
         */
        public String labelZh() {
            return labelZh;
        }

        /**
         * @return 稳定英文键，便于前端对齐
         */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}

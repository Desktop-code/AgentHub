package com.agenthub.model.infra.adapter;

/**
 * DashScope 原生 API 路径。所属域：model。
 * 文本排序 / 原生 embedding / generation 挂在 /api/v1；与 compatible-mode 基址无关。
 * 连通测与 RAG Rerank 出站必须共用此处，避免 Rerank 误打 Chat。
 */
public final class DashScopeNativePaths {

    /** 原生文本生成 */
    public static final String TEXT_GENERATION = "/services/aigc/text-generation/generation";
    /** 原生多模态生成：视觉（qwen-vl）与同步语音识别（qwen3-asr-flash）共用 */
    public static final String MULTIMODAL_GENERATION = "/services/aigc/multimodal-generation/generation";
    /** 原生向量嵌入 */
    public static final String TEXT_EMBEDDING = "/services/embeddings/text-embedding/text-embedding";
    /** 原生文本排序（qwen3-rerank / gte-rerank 等） */
    public static final String TEXT_RERANK = "/services/rerank/text-rerank/text-rerank";
    /** 原生语音合成（qwen-audio-tts / cosyvoice 等） */
    public static final String SPEECH_SYNTHESIZER = "/services/audio/tts/SpeechSynthesizer";

    private DashScopeNativePaths() {
    }

    /**
     * 从供应商 baseUrl 抽出 {@code .../api/v1} 根。
     * 用户若填了 compatible-mode 或裸域名，Rerank 仍应打原生 /api/v1，不能跟兼容前缀走。
     *
     * @param baseUrl 供应商地址，可含 compatible-mode
     * @return 以 /api/v1 结尾的根（无尾斜杠）
     */
    public static String apiV1Root(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        String base = baseUrl.trim();
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        int index = base.indexOf("/api/v1");
        if (index >= 0) {
            return base.substring(0, index) + "/api/v1";
        }
        // compatible-mode/v1 仍落在同一主机；剥到主机再挂 /api/v1
        int compatible = base.indexOf("/compatible-mode");
        if (compatible >= 0) {
            return base.substring(0, compatible) + "/api/v1";
        }
        return base + "/api/v1";
    }

    /**
     * @param baseUrl 供应商地址
     * @param path    以 / 开头的原生相对路径
     * @return 完整 URL
     */
    public static String join(String baseUrl, String path) {
        String root = apiV1Root(baseUrl);
        String suffix = path.startsWith("/") ? path : "/" + path;
        return root + suffix;
    }
}

package com.agenthub.rag.domain.service;

import com.agenthub.spi.rag.SearchQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 检索参数解析与收紧。所属域：rag。
 * <p>
 * 参数来源优先级：Agent 绑定覆盖项 &gt; KB 的 retrieval_config &gt; 平台默认。
 * 所有数值都在这里夹紧到硬上限，避免 Agent 配置把 topK 或召回条数放大到打爆上下文/向量库。
 * <p>
 * JSON 解析失败只记日志并回落默认，不让一段坏配置把整次检索打掉。
 */
public final class RetrievalConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(RetrievalConfigResolver.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 最终返回条数默认值 */
    public static final int DEFAULT_TOP_K = 5;
    /** 最终返回条数硬上限：超过这个数会把上下文挤爆 */
    public static final int MAX_TOP_K = 20;
    /** 单路召回条数默认值 */
    public static final int DEFAULT_RECALL_TOP_K = 20;
    /** 单路召回条数硬上限 */
    public static final int MAX_RECALL_TOP_K = 100;
    /** RRF k 默认值 */
    public static final int DEFAULT_RRF_K = 60;
    /** RRF 融合后条数默认值 */
    public static final int DEFAULT_RRF_TOP_N = 20;
    /** 向量分下限默认值。BGE 类模型 IP 分数常在 0.3~0.7，0.5 是较稳的起点 */
    public static final double DEFAULT_SCORE_THRESHOLD = 0.5d;

    private RetrievalConfigResolver() {
    }

    /**
     * 合成检索参数。
     *
     * @param kbRetrievalConfig KB 的 retrieval_config JSON，可空
     * @param overridesJson     Agent 绑定的 overrides_json，可空；值为 null 表示该键不覆盖
     * @param topKOverride      调用方（Tool 入参）显式给的 topK，≤0 表示不覆盖
     * @param kbId              知识库 id
     * @param query             检索句
     * @param tenantId          租户过滤，可空
     * @return 已夹紧的检索参数
     */
    public static SearchQuery resolve(String kbRetrievalConfig, String overridesJson, int topKOverride,
                                      Long kbId, String query, Long tenantId) {
        JsonNode kb = parse(kbRetrievalConfig);
        JsonNode over = parse(overridesJson);

        int topK = pickInt(over, kb, "topK", DEFAULT_TOP_K);
        if (topKOverride > 0) {
            topK = topKOverride;
        }
        topK = clamp(topK, 1, MAX_TOP_K);

        // 召回条数不得小于最终条数，否则融合后凑不满 topK
        int vectorTopK = clamp(pickInt(over, kb, "vectorTopK", DEFAULT_RECALL_TOP_K), 1, MAX_RECALL_TOP_K);
        int bm25TopK = clamp(pickInt(over, kb, "bm25TopK", DEFAULT_RECALL_TOP_K), 1, MAX_RECALL_TOP_K);
        int rrfTopN = clamp(Math.max(pickInt(over, kb, "rrfTopN", DEFAULT_RRF_TOP_N), topK), 1, MAX_RECALL_TOP_K);
        int rrfK = clamp(pickInt(over, kb, "rrfK", DEFAULT_RRF_K), 1, 1000);
        double threshold = clampDouble(pickDouble(over, kb, "scoreThreshold", DEFAULT_SCORE_THRESHOLD), 0d, 1d);

        SearchQuery sq = new SearchQuery();
        sq.setKbId(kbId);
        sq.setQuery(query);
        sq.setTopK(topK);
        sq.setVectorTopK(Math.max(vectorTopK, topK));
        sq.setBm25TopK(Math.max(bm25TopK, topK));
        sq.setRrfTopN(rrfTopN);
        sq.setRrfK(rrfK);
        sq.setRrfEnabled(pickBool(over, kb, "rrfEnabled", true));
        // Rerank 默认关：未配模型或未显式打开时不打精排出站
        sq.setRerankEnabled(pickBool(over, kb, "rerankEnabled", false));
        sq.setScoreThreshold(threshold);
        sq.setTenantId(tenantId);
        return sq;
    }

    /**
     * 读 JSON 对象；空或非法返回空节点，调用方按默认值处理。
     */
    static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : JSON.createObjectNode();
        } catch (Exception e) {
            log.warn("[RAG] 检索配置不是合法 JSON，按默认处理: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    /**
     * 优先取覆盖项，其次 KB，最后默认。
     */
    private static int pickInt(JsonNode over, JsonNode kb, String key, int fallback) {
        JsonNode v = valueOf(over, kb, key);
        return v != null && v.isNumber() ? v.asInt() : fallback;
    }

    private static double pickDouble(JsonNode over, JsonNode kb, String key, double fallback) {
        JsonNode v = valueOf(over, kb, key);
        return v != null && v.isNumber() ? v.asDouble() : fallback;
    }

    private static boolean pickBool(JsonNode over, JsonNode kb, String key, boolean fallback) {
        JsonNode v = valueOf(over, kb, key);
        return v != null && v.isBoolean() ? v.asBoolean() : fallback;
    }

    /**
     * 覆盖项里显式给 null 表示「本键不覆盖」，交回 KB 处理。
     */
    private static JsonNode valueOf(JsonNode over, JsonNode kb, String key) {
        if (over != null && over.has(key) && !over.get(key).isNull()) {
            return over.get(key);
        }
        if (kb != null && kb.has(key) && !kb.get(key).isNull()) {
            return kb.get(key);
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.min(Math.max(value, min), max);
    }

    /**
     * 供单测与文档展示的默认参数。
     *
     * @return 只读默认值
     */
    public static Map<String, Object> defaults() {
        return Map.of(
                "topK", DEFAULT_TOP_K,
                "vectorTopK", DEFAULT_RECALL_TOP_K,
                "bm25TopK", DEFAULT_RECALL_TOP_K,
                "rrfTopN", DEFAULT_RRF_TOP_N,
                "rrfK", DEFAULT_RRF_K,
                "scoreThreshold", DEFAULT_SCORE_THRESHOLD,
                "rrfEnabled", true,
                "rerankEnabled", false);
    }
}

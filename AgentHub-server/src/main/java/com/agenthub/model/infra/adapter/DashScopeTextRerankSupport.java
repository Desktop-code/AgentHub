package com.agenthub.model.infra.adapter;

import com.agenthub.spi.rag.RerankException;
import com.agenthub.spi.rag.RerankHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope 文本排序报文与解析。所属域：model。
 * 连通测与 RAG {@code ModelCenterRerankClient} 共用路径与 body 形状，避免两套实现漂移。
 */
public final class DashScopeTextRerankSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DashScopeTextRerankSupport() {
    }

    /**
     * @param baseUrl 供应商地址（可含 compatible-mode，内部仍转原生 /api/v1）
     * @return text-rerank 完整 URL
     */
    public static String url(String baseUrl) {
        return DashScopeNativePaths.join(baseUrl, DashScopeNativePaths.TEXT_RERANK);
    }

    /**
     * 构造出站 JSON：model + input.query + input.documents + parameters.top_n。
     *
     * @param modelId   厂商模型 id（如 qwen3-rerank）
     * @param query     查询句
     * @param documents 候选文档
     * @param topN      返回条数上限；≤0 表示全部
     * @return JSON 对象（交给调用方序列化）
     */
    public static Map<String, Object> requestMap(String modelId, String query,
                                                 List<String> documents, int topN) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query == null ? "" : query);
        input.put("documents", documents == null ? List.of() : documents);
        Map<String, Object> parameters = new LinkedHashMap<>();
        int n = topN <= 0 ? (documents == null ? 0 : documents.size()) : topN;
        if (n > 0) {
            parameters.put("top_n", n);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("input", input);
        body.put("parameters", parameters);
        return body;
    }

    /**
     * 解析 {@code output.results[]}：取 index + relevance_score，按分数降序。
     *
     * @param responseBody 上游响应原文
     * @return 命中列表
     * @throws RerankException 结构不合法
     */
    public static List<RerankHit> parseResults(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new RerankException("重排返回空响应");
        }
        JsonNode root;
        try {
            root = JSON.readTree(responseBody);
        } catch (Exception ex) {
            throw new RerankException("重排响应不是合法 JSON", ex);
        }
        JsonNode results = root.path("output").path("results");
        if (!results.isArray()) {
            // 部分网关可能扁平返回 results
            results = root.path("results");
        }
        if (!results.isArray()) {
            throw new RerankException("重排响应缺少 output.results");
        }
        List<RerankHit> hits = new ArrayList<>(results.size());
        for (JsonNode item : results) {
            if (item == null || !item.has("index")) {
                continue;
            }
            int index = item.get("index").asInt(-1);
            if (index < 0) {
                continue;
            }
            double score = item.path("relevance_score").asDouble(item.path("score").asDouble(0d));
            hits.add(new RerankHit(index, score));
        }
        hits.sort(Comparator.comparingDouble(RerankHit::score).reversed());
        return hits;
    }
}

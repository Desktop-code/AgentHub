package com.agenthub.model.infra.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从向量嵌入探活响应里解析向量维度。所属域：model。
 * <p>
 * 只用于「最近一次连通测试成功快照」，不参与运行时写入；解析失败返回 null，
 * 由上层把快照置空，使 memory 域进入「无维快照 → 不 isReady」。
 * <p>
 * 兼容两种常见报文：
 * <ul>
 *   <li>OpenAI 兼容（含 DashScope compatible-mode）：{@code data[0].embedding}</li>
 *   <li>DashScope 原生：{@code output.embeddings[0].embedding}</li>
 * </ul>
 * 禁止把解析失败抛异常：连通测试本身已成功时，维度缺失只降级为「未就绪」。
 */
final class EmbeddingProbeDim {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 工具类禁止实例化。
     */
    private EmbeddingProbeDim() {
    }

    /**
     * 解析响应体里的向量长度。
     *
     * @param body 2xx 响应体，可为空
     * @return 维度；报文缺失/结构不符/数组为空时返回 null
     */
    static Integer parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            // 先按 OpenAI 兼容形态取，再按 DashScope 原生形态兜底
            Integer openAi = lengthOf(root.path("data"));
            if (openAi != null) {
                return openAi;
            }
            return lengthOf(root.path("output").path("embeddings"));
        } catch (Exception ex) {
            // 非 JSON 或结构异常：快照留空，不打断连通测试结论
            return null;
        }
    }

    /**
     * 取第一个元素的 embedding 数组长度。
     *
     * @param array 期望为数组的节点
     * @return 维度；非数组、空数组或首元素无 embedding 时为 null
     */
    private static Integer lengthOf(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        JsonNode embedding = array.get(0).path("embedding");
        if (!embedding.isArray() || embedding.isEmpty()) {
            return null;
        }
        return embedding.size();
    }
}

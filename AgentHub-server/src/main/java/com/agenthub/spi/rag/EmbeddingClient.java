package com.agenthub.spi.rag;

/**
 * 一次 Embedding 出站调用。所属域：spi。
 * 调用方：rag 入库与检索；实现方：默认走模型中心的 OpenAI 兼容 /embeddings。
 * <p>
 * 关键约束：返回向量长度必须等于 {@code expectedDimension}，不允许静默截断或补零——
 * 维度不符会让 Milvus 写入永久失败，且在检索时产生无意义相似度，必须尽早报错。
 */
public interface EmbeddingClient {

    /**
     * 批量向量化。
     *
     * @param modelInstanceId 模型实例 id（KB 上记录的 embedding_model_id）
     * @param texts           待向量化文本，至少一条
     * @param expectedDimension 期望维度；>0 时实现必须校验
     * @return 与 texts 等长的向量数组，顺序一致
     * @throws EmbeddingException Key 缺失、出站失败、维度不符
     */
    float[][] embed(Long modelInstanceId, java.util.List<String> texts, int expectedDimension);

    /**
     * 单条向量化，等价于 {@code embed(id, List.of(text), dim)[0]}。
     *
     * @param modelInstanceId 模型实例 id
     * @param text            文本
     * @param expectedDimension 期望维度
     * @return 向量
     * @throws EmbeddingException 同上
     */
    default float[] embedOne(Long modelInstanceId, String text, int expectedDimension) {
        float[][] batch = embed(modelInstanceId, java.util.List.of(text), expectedDimension);
        if (batch == null || batch.length != 1) {
            throw new EmbeddingException("向量化返回条数与请求不一致");
        }
        return batch[0];
    }

    /**
     * @return 批量调用单次上限，供上层分批
     */
    default int maxBatchSize() {
        return 16;
    }
}

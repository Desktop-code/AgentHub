package com.agenthub.spi.rag;

import java.util.List;

/**
 * 文本重排出站。所属域：spi。
 * 调用方：RAG 检索精排；实现方：按模型中心协议×用途矩阵路由（当前真路径：DASHSCOPE text-rerank）。
 * 未适配协议必须抛 {@link RerankException}（文案含「用途未适配」），禁止改打 Chat。
 */
public interface RerankClient {

    /**
     * 按查询对候选文档重排。
     *
     * @param modelInstanceId 用途须为 RERANK 的模型实例 id
     * @param query           检索句
     * @param documents       候选正文，顺序与返回 index 对齐
     * @param topN            最多返回条数；≤0 表示全部
     * @return 按分数降序的命中；可能短于 documents
     * @throws RerankException 配置缺失、用途未适配、出站失败
     */
    List<RerankHit> rerank(Long modelInstanceId, String query, List<String> documents, int topN);
}

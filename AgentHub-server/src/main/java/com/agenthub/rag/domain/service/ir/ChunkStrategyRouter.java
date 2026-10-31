package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 按 {@link DocumentType} 路由到对应 {@link ChunkStrategy}。
 */
public class ChunkStrategyRouter {

    private final Map<DocumentType, ChunkStrategy> strategies = new EnumMap<>(DocumentType.class);

    public ChunkStrategyRouter(List<ChunkStrategy> strategyList) {
        if (strategyList != null) {
            for (ChunkStrategy strategy : strategyList) {
                if (strategy != null && strategy.supportedType() != null) {
                    strategies.put(strategy.supportedType(), strategy);
                }
            }
        }
    }

    public List<DocumentChunk> route(StructuredDocument doc) {
        DocumentType type = doc == null || doc.getDocType() == null ? DocumentType.NARRATIVE : doc.getDocType();
        ChunkStrategy strategy = strategies.get(type);
        if (strategy == null) {
            strategy = strategies.get(DocumentType.NARRATIVE);
        }
        if (strategy == null) {
            throw new IllegalStateException("未找到对应的切片策略: " + type);
        }
        return strategy.chunk(doc);
    }
}

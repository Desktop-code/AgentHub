package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;

import java.util.List;

/**
 * 按结构原型切片。
 */
public interface ChunkStrategy {

    DocumentType supportedType();

    List<DocumentChunk> chunk(StructuredDocument doc);
}

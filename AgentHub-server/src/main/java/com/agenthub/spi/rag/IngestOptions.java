package com.agenthub.spi.rag;

/**
 * 入库参数占位。所属域：spi。② 期实装时在此补 chunkSize / overlap / 解析策略。
 * 一期只为让 {@link RagProvider#ingestDocument} 签名稳定，不承载任何逻辑。
 */
public class IngestOptions {

    /** 目标切片长度，null 表示用 KB 的 ingest_config */
    private Integer chunkSize;
    /** 切片重叠，null 表示用 KB 的 ingest_config */
    private Integer chunkOverlap;

    /**
     * @return 目标切片长度
     */
    public Integer getChunkSize() {
        return chunkSize;
    }

    /**
     * @param chunkSize 目标切片长度
     */
    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    /**
     * @return 切片重叠
     */
    public Integer getChunkOverlap() {
        return chunkOverlap;
    }

    /**
     * @param chunkOverlap 切片重叠
     */
    public void setChunkOverlap(Integer chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }
}

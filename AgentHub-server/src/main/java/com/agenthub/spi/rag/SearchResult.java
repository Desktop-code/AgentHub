package com.agenthub.spi.rag;

/**
 * 检索命中。所属域：spi。调用方：RAG 检索 Tool；实现方：RagProvider。
 * content 由 rag 域用 MySQL 的完整原文回填（Milvus 里只是截断副本）。
 * 四种分数并存：vector / bm25 / rrf / rerank，score 取优先级最高的那个，便于模型与排障都看得懂。
 */
public class SearchResult {

    /** 切片 id（= MySQL rag_chunk.id = Milvus 主键） */
    private Long chunkId;
    /** 文档 id */
    private Long docId;
    /** 知识库 id */
    private Long kbId;
    /** 文档标题 */
    private String title;
    /** 来源（文件名或 URI） */
    private String source;
    /** 切片序号，便于按原文顺序阅读 */
    private Integer chunkIndex;
    /** 完整正文 */
    private String content;
    /** 向量相似度；纯 BM25 命中时为 null */
    private Double vectorScore;
    /** BM25 分；纯向量命中时为 null */
    private Double bm25Score;
    /** RRF 融合分；未融合时为 null */
    private Double rrfScore;
    /** 精排分；未精排时为 null */
    private Double rerankScore;
    /** 命中来源：vector / bm25 / rrf / rerank，用于展示与排障 */
    private String hitBy;

    /**
     * 对外展示分：精排 > RRF > 向量/BM25 取大。
     *
     * @return 用于展示与排序的分数，全部缺失时为 0
     */
    public double primaryScore() {
        if (rerankScore != null) {
            return rerankScore;
        }
        if (rrfScore != null) {
            return rrfScore;
        }
        if (vectorScore != null && bm25Score != null) {
            return Math.max(vectorScore, bm25Score);
        }
        if (vectorScore != null) {
            return vectorScore;
        }
        if (bm25Score != null) {
            return bm25Score;
        }
        return 0d;
    }

    /**
     * @return 切片 id
     */
    public Long getChunkId() {
        return chunkId;
    }

    /**
     * @param chunkId 切片 id
     */
    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    /**
     * @return 文档 id
     */
    public Long getDocId() {
        return docId;
    }

    /**
     * @param docId 文档 id
     */
    public void setDocId(Long docId) {
        this.docId = docId;
    }

    /**
     * @return 知识库 id
     */
    public Long getKbId() {
        return kbId;
    }

    /**
     * @param kbId 知识库 id
     */
    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    /**
     * @return 标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * @param title 标题
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * @return 来源
     */
    public String getSource() {
        return source;
    }

    /**
     * @param source 来源
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * @return 切片序号
     */
    public Integer getChunkIndex() {
        return chunkIndex;
    }

    /**
     * @param chunkIndex 切片序号
     */
    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    /**
     * @return 完整正文
     */
    public String getContent() {
        return content;
    }

    /**
     * @param content 完整正文
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * @return 向量相似度
     */
    public Double getVectorScore() {
        return vectorScore;
    }

    /**
     * @param vectorScore 向量相似度
     */
    public void setVectorScore(Double vectorScore) {
        this.vectorScore = vectorScore;
    }

    /**
     * @return BM25 分
     */
    public Double getBm25Score() {
        return bm25Score;
    }

    /**
     * @param bm25Score BM25 分
     */
    public void setBm25Score(Double bm25Score) {
        this.bm25Score = bm25Score;
    }

    /**
     * @return RRF 分
     */
    public Double getRrfScore() {
        return rrfScore;
    }

    /**
     * @param rrfScore RRF 分
     */
    public void setRrfScore(Double rrfScore) {
        this.rrfScore = rrfScore;
    }

    /**
     * @return 精排分
     */
    public Double getRerankScore() {
        return rerankScore;
    }

    /**
     * @param rerankScore 精排分
     */
    public void setRerankScore(Double rerankScore) {
        this.rerankScore = rerankScore;
    }

    /**
     * @return 命中来源
     */
    public String getHitBy() {
        return hitBy;
    }

    /**
     * @param sourceType 命中来源
     */
    public void setHitBy(String hitBy) {
        this.hitBy = hitBy;
    }
}


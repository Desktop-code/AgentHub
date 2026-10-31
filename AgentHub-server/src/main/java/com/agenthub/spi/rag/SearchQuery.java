package com.agenthub.spi.rag;

/**
 * 检索请求。所属域：spi。调用方：RAG 检索 Tool 与 ③ 期检索服务；实现方：RagProvider。
 * 只带已经解析好的参数，不再引用 KB / 用户对象，便于单测与跨实现复用。
 */
public class SearchQuery {

    /** 知识库 id；PHYSICAL 下决定集合，检索时强制作为过滤条件 */
    private Long kbId;
    /** 检索句 */
    private String query;
    /** 最终返回条数 */
    private int topK;
    /** 向量路召回条数；≤0 表示用默认 */
    private int vectorTopK;
    /** BM25 路召回条数；≤0 表示用默认，能力不支持时忽略 */
    private int bm25TopK;
    /** RRF 平滑常数 k，常见 60 */
    private int rrfK;
    /** RRF 融合后条数 */
    private int rrfTopN;
    /** 是否做 RRF；false 表示只用向量排序 */
    private boolean rrfEnabled;
    /** 是否调用 Rerank 模型精排；默认关，且需 KB 配置了 rerankModelId */
    private boolean rerankEnabled;
    /** 向量分下限；≤0 表示不过滤（0 分也可能是合法命中） */
    private double scoreThreshold;
    /** 租户过滤；null 表示不过滤 */
    private Long tenantId;

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
     * @return 检索句
     */
    public String getQuery() {
        return query;
    }

    /**
     * @param query 检索句
     */
    public void setQuery(String query) {
        this.query = query;
    }

    /**
     * @return 返回条数
     */
    public int getTopK() {
        return topK;
    }

    /**
     * @param topK 返回条数
     */
    public void setTopK(int topK) {
        this.topK = topK;
    }

    /**
     * @return 向量路召回条数
     */
    public int getVectorTopK() {
        return vectorTopK;
    }

    /**
     * @param vectorTopK 向量路召回条数
     */
    public void setVectorTopK(int vectorTopK) {
        this.vectorTopK = vectorTopK;
    }

    /**
     * @return BM25 路召回条数
     */
    public int getBm25TopK() {
        return bm25TopK;
    }

    /**
     * @param bm25TopK BM25 路召回条数
     */
    public void setBm25TopK(int bm25TopK) {
        this.bm25TopK = bm25TopK;
    }

    /**
     * @return RRF k
     */
    public int getRrfK() {
        return rrfK;
    }

    /**
     * @param rrfK RRF k
     */
    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    /**
     * @return RRF 融合后条数
     */
    public int getRrfTopN() {
        return rrfTopN;
    }

    /**
     * @param rrfTopN RRF 融合后条数
     */
    public void setRrfTopN(int rrfTopN) {
        this.rrfTopN = rrfTopN;
    }

    /**
     * @return 是否启用 RRF
     */
    public boolean isRrfEnabled() {
        return rrfEnabled;
    }

    /**
     * @param rrfEnabled 是否启用 RRF
     */
    public void setRrfEnabled(boolean rrfEnabled) {
        this.rrfEnabled = rrfEnabled;
    }

    /**
     * @return 是否启用 Rerank 精排
     */
    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    /**
     * @param rerankEnabled 是否启用 Rerank
     */
    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    /**
     * @return 向量分下限
     */
    public double getScoreThreshold() {
        return scoreThreshold;
    }

    /**
     * @param scoreThreshold 向量分下限
     */
    public void setScoreThreshold(double scoreThreshold) {
        this.scoreThreshold = scoreThreshold;
    }

    /**
     * @return 租户过滤
     */
    public Long getTenantId() {
        return tenantId;
    }

    /**
     * @param tenantId 租户过滤
     */
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}

package com.agenthub.rag.domain.model;

/**
 * Collection 实际能力。所属域：rag。
 * 本机 Milvus Lite 不支持 SPARSE_FLOAT_VECTOR，建集会自动降级为纯向量，此处把真实能力落库，
 * 供管理员判断「该库能否做关键词召回」，也避免 ③ 期误以为 BM25 可用。
 */
public enum RagStoreCapability {

    /** dense 向量 + BM25 sparse 全文，可做向量 ∥ BM25 → RRF */
    DENSE_BM25("DENSE_BM25"),
    /** 仅 dense 向量；MySQL ngram 可作 ③ 的关键词兜底 */
    DENSE_ONLY("DENSE_ONLY");

    private final String code;

    RagStoreCapability(String code) {
        this.code = code;
    }

    /**
     * @return 入库字符串
     */
    public String getCode() {
        return code;
    }

    /**
     * @param raw 库中的值
     * @return 枚举；null 或未知按 DENSE_ONLY，宁可按能力弱处理
     */
    public static RagStoreCapability parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DENSE_ONLY;
        }
        String key = raw.trim().toUpperCase();
        for (RagStoreCapability value : values()) {
            if (value.code.equals(key)) {
                return value;
            }
        }
        return DENSE_ONLY;
    }
}

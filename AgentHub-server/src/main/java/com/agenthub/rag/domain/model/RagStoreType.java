package com.agenthub.rag.domain.model;

/**
 * 知识库存储类型。所属域：rag。一期只实装 LOCAL_MILVUS，其余仅保留枚举供后排。
 */
public enum RagStoreType {

    /** 本机 / 自建 Milvus，一期唯一可选项 */
    LOCAL_MILVUS("LOCAL_MILVUS"),
    /** Dify 数据集 API，后排；选择即拒绝创建 */
    DIFY_API("DIFY_API"),
    /** 百炼知识库 API，后排；选择即拒绝创建 */
    BAILIAN_API("BAILIAN_API");

    private final String code;

    RagStoreType(String code) {
        this.code = code;
    }

    /**
     * @return 入库字符串
     */
    public String getCode() {
        return code;
    }

    /**
     * 解析：空白按 LOCAL_MILVUS（一期唯一实装类型，前端不传时不应失败）；未知值抛异常。
     *
     * @param raw 前端传入
     * @return 枚举
     * @throws IllegalArgumentException 不支持的类型
     */
    public static RagStoreType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LOCAL_MILVUS;
        }
        String key = raw.trim().toUpperCase();
        for (RagStoreType value : values()) {
            if (value.code.equals(key)) {
                return value;
            }
        }
        throw new IllegalArgumentException("不支持的存储类型: " + raw);
    }

    /**
     * @return 一期是否可真正建库
     */
    public boolean implemented() {
        return this == LOCAL_MILVUS;
    }
}

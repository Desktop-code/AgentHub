package com.agenthub.rag.domain.model;

/**
 * 文档状态。所属域：rag。落库为字符串，与 rag_document.status 一致。
 */
public enum RagDocumentStatus {

    /** 已上传待入库 */
    PENDING("PENDING"),
    /** 入库中 */
    RUNNING("RUNNING"),
    /** 可检索 */
    READY("READY"),
    /** 入库失败，可重试 */
    FAILED("FAILED");

    private final String code;

    RagDocumentStatus(String code) {
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
     * @return 枚举；未知或空按 PENDING
     */
    public static RagDocumentStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PENDING;
        }
        String key = raw.trim().toUpperCase();
        for (RagDocumentStatus value : values()) {
            if (value.code.equals(key)) {
                return value;
            }
        }
        return PENDING;
    }
}

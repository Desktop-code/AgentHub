package com.agenthub.rag.domain.model;

/**
 * 知识库初始化状态。所属域：rag。落库为字符串，与 rag_knowledge_base.status 一致。
 */
public enum RagKbStatus {

    /** 建集成功，可入库、可被 Agent 绑定 */
    READY("READY"),
    /** 建集失败（Milvus 不可用 / 维度非法等）；管理员可重建 */
    INIT_FAILED("INIT_FAILED"),
    /** 管理员主动停用；检索与绑定都不应命中 */
    DISABLED("DISABLED");

    private final String code;

    RagKbStatus(String code) {
        this.code = code;
    }

    /**
     * @return 入库字符串
     */
    public String getCode() {
        return code;
    }

    /**
     * 宽松解析：大小写不敏感，未知值按 INIT_FAILED，避免脏数据被当成可用。
     *
     * @param raw 库中的 status
     * @return 枚举；null 或未知值时 INIT_FAILED
     */
    public static RagKbStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return INIT_FAILED;
        }
        String key = raw.trim().toUpperCase();
        for (RagKbStatus value : values()) {
            if (value.code.equals(key)) {
                return value;
            }
        }
        return INIT_FAILED;
    }
}

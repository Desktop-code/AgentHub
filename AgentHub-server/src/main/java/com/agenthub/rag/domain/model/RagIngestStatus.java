package com.agenthub.rag.domain.model;

/**
 * 入库任务状态。所属域：rag。落库为字符串，与 rag_ingest_task.status 一致。
 */
public enum RagIngestStatus {

    /** 已创建待执行 */
    PENDING("PENDING"),
    /** 正在执行 */
    RUNNING("RUNNING"),
    /** 执行成功 */
    SUCCESS("SUCCESS"),
    /** 执行失败，可重试 */
    FAILED("FAILED");

    private final String code;

    RagIngestStatus(String code) {
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
     * @return 枚举；未知或空按 PENDING，避免脏数据被当成成功
     */
    public static RagIngestStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PENDING;
        }
        String key = raw.trim().toUpperCase();
        for (RagIngestStatus value : values()) {
            if (value.code.equals(key)) {
                return value;
            }
        }
        return PENDING;
    }

    /**
     * @return 是否终态（不可再推进，只能重试或删除）
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}

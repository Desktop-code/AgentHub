package com.agenthub.rag.domain.model;

/**
 * 隔离模式。所属域：rag。一期只落地 PHYSICAL（一 KB 一 Collection）；其余仅保留枚举。
 */
public enum RagIsolationMode {

    /** 一 KB 一 Collection，机密库首选；一期唯一可选 */
    PHYSICAL("PHYSICAL"),
    /** 多 KB 共 Collection，检索强制 kb_id filter——二期 */
    LOGICAL("LOGICAL"),
    /** 公开池，与 PHYSICAL 共用「一库一集」实现——一期可选 */
    PUBLIC("PUBLIC");

    private final String code;

    RagIsolationMode(String code) {
        this.code = code;
    }

    /**
     * @return 入库字符串
     */
    public String getCode() {
        return code;
    }

    /**
     * @param raw 前端传入，空则按 PHYSICAL
     * @return 枚举
     * @throws IllegalArgumentException 不支持的模式
     */
    public static RagIsolationMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PHYSICAL;
        }
        String key = raw.trim().toUpperCase();
        for (RagIsolationMode value : values()) {
            if (value.code.equals(key)) {
                return value;
            }
        }
        throw new IllegalArgumentException("不支持的隔离模式: " + raw);
    }

    /**
     * 一期是否可创建。LOGICAL 需要共集合 + kb_id 过滤，明确不做。
     *
     * @return true 表示本期可落地
     */
    public boolean availableNow() {
        return this != LOGICAL;
    }
}

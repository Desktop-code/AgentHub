package com.agenthub.rag.domain.model;

/**
 * 知识库可见性。所属域：rag。一期只落地 PUBLIC / PRIVATE；RESTRICTED 需 rag_kb_grant，二期开。
 */
public enum RagVisibility {

    /** 所有能进入控制台的用户可见 */
    PUBLIC("PUBLIC"),
    /** 仅创建人与管理员可见 */
    PRIVATE("PRIVATE"),
    /** 按 rag_kb_grant 授权，二期 */
    RESTRICTED("RESTRICTED");

    private final String code;

    RagVisibility(String code) {
        this.code = code;
    }

    /**
     * @return 入库字符串
     */
    public String getCode() {
        return code;
    }

    /**
     * @param raw 前端传入，空则按 PRIVATE
     * @return 枚举
     * @throws IllegalArgumentException 不支持的值
     */
    public static RagVisibility parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PRIVATE;
        }
        String key = raw.trim().toUpperCase();
        for (RagVisibility value : values()) {
            if (value.code.equals(key)) {
                return value;
            }
        }
        throw new IllegalArgumentException("不支持的可见性: " + raw);
    }

    /**
     * 一期是否可创建。RESTRICTED 未接授权表，先拒绝，避免建出「看着私密其实全开」的库。
     *
     * @return true 表示本期可落地
     */
    public boolean availableNow() {
        return this != RESTRICTED;
    }
}

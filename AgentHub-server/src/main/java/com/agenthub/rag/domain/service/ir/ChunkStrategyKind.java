package com.agenthub.rag.domain.service.ir;

/**
 * 现网四种结构感知切片策略。所属域：rag 的 ir。
 * <p>
 * code 落库到 {@code rag_chunk.strategy}，label 是前端唯一允许展示的中文标签；
 * 只用于展示与归因，不参与路由判定（路由仍由 {@link DocumentType} + {@link ChunkStrategyRouter} 决定）。
 */
public enum ChunkStrategyKind {

    /** 层级型：按 Markdown/章节标题切 */
    HIERARCHICAL("层级"),
    /** 条目型：按条目切父块、按字段切子块 */
    ENTRY_BASED("条目"),
    /** 表格型：按行切、保留表头 */
    TABULAR("表格"),
    /** 叙述型：按页/段落递归切 */
    NARRATIVE("叙述");

    private final String label;

    ChunkStrategyKind(String label) {
        this.label = label;
    }

    /** @return 落库/接口用的稳定编码 */
    public String code() {
        return name();
    }

    /** @return 前端展示用中文标签 */
    public String label() {
        return label;
    }

    /**
     * 把落库编码翻译成中文标签；未知编码原样返回，旧数据为空时返回空串。
     *
     * @param code 策略编码
     * @return 中文标签或原值
     */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        for (ChunkStrategyKind kind : values()) {
            if (kind.name().equalsIgnoreCase(code.trim())) {
                return kind.label();
            }
        }
        return code.trim();
    }
}

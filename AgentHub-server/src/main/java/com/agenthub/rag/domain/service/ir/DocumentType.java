package com.agenthub.rag.domain.service.ir;

/**
 * 文档结构原型。切片按此路由，不再按文件扩展名硬编码。
 */
public enum DocumentType {
    /** 条目型：FAQ、词典、中药手册 */
    ENTRY_BASED,
    /** 层级型：Markdown、政策文件 */
    HIERARCHICAL,
    /** 叙述型：论文、报告 */
    NARRATIVE,
    /** 表格型：Excel 导出、数据表 */
    TABULAR
}

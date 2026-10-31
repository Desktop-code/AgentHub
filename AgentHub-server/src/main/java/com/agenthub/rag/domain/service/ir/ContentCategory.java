package com.agenthub.rag.domain.service.ir;

/**
 * 切片内容主题分类（与文档上传 category 分离，避免售后/FAQ 等文档分类被覆盖）。
 * 检索侧按意图优先过滤本字段。
 */
public enum ContentCategory {
    /** 疾病 / 症状 */
    DISEASE,
    /** 中药材 */
    HERB,
    /** 茶方 / 药茶配方 */
    TEA_RECIPE,
    /** 茶饮基础知识（调制方法等，检索症状时通常应排除） */
    TEA_BASE,
    /** 未识别 */
    OTHER
}

package com.agenthub.rag.domain.repository;

import com.agenthub.rag.domain.model.RagDocumentParse;

import java.util.Optional;

/**
 * 文档解析文本产物仓储。所属域：rag。
 * <p>
 * 一篇文档只有一份解析产物，写入是「按 doc_id upsert」：重灌与重切都覆盖同一行，
 * 读侧只关心当前这轮实际使用的文本。
 */
public interface RagDocumentParseRepository {

    /**
     * @param docId 文档
     * @return 当前解析产物；老文档或未入库成功时为空
     */
    Optional<RagDocumentParse> findByDocId(Long docId);

    /**
     * 按 doc_id 覆盖保存：存在则更新正文与统计，不存在则插入。
     *
     * @param parse 解析产物
     */
    void save(RagDocumentParse parse);

    /**
     * 只更新结构识别统计，避免再次写整段 LONGTEXT。
     *
     * @param docId       文档
     * @param headerCount 标题数
     * @param docType     IR 原型
     * @param strategy    实际切片策略
     */
    void updateStats(Long docId, Integer headerCount, String docType, String strategy);

    /**
     * 物理删除某文档的解析产物。删文档、删知识库时调用。
     *
     * @param docId 文档
     * @return 删除行数
     */
    int deleteByDocId(Long docId);

    /**
     * 物理删除整个知识库的解析产物。删库时调用，避免残留全文。
     *
     * @param kbId 知识库
     * @return 删除行数
     */
    int deleteByKb(Long kbId);
}

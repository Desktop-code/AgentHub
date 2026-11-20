package com.agenthub.rag.domain.repository;

import com.agenthub.rag.domain.model.RagChunk;

import java.util.List;

/**
 * 切片仓储。所属域：rag。
 * 插入后必须回填自增 id（Milvus 主键依赖它），因此 {@link #insertBatch} 返回带 id 的列表。
 */
public interface RagChunkRepository {

    /**
     * 批量插入并回填 id。逐条插入以拿到自增主键；单文档切片量级在千以内，可接受。
     *
     * @param chunks    切片，id 字段将被回填
     * @param operatorId 写入人
     * @return 同一批对象（已带 id），顺序与入参一致
     */
    List<RagChunk> insertBatch(List<RagChunk> chunks, Long operatorId);

    /**
     * @param docId 文档
     * @return 该文档全部切片，按序号升序；含 deleted=1 的历史行
     */
    List<RagChunk> listByDocId(Long docId);

    /**
     * 按主键取单条切片。单条编辑前必须校验它确实属于目标文档，不能只信前端 id。
     *
     * @param id 切片主键
     * @return 切片；不存在时为空
     */
    java.util.Optional<RagChunk> findById(Long id);

    /**
     * 只更新一条切片的正文与派生字段（Token、哈希、版本、章节路径）。
     * <p>
     * 单条编辑不允许改序号、父块与偏移，避免与整篇重切的两代切片语义混淆。
     *
     * @param id          切片主键
     * @param content     新正文（非空）
     * @param tokenEst    Token 估算
     * @param contentHash 新正文哈希
     * @param version     新版本号
     * @param sectionPath 章节路径，null 表示不改
     * @return 影响行数
     */
    int updateContent(Long id, String content, Integer tokenEst, String contentHash,
                      Integer version, String sectionPath);

    /**
     * 按切片 id 批量取正文。检索命中后回填完整原文用。
     *
     * @param ids 切片 id
     * @return 命中的切片，缺失的 id 不会出现在结果里
     */
    List<RagChunk> listByIds(List<Long> ids);

    /**
     * 物理删除某文档的全部切片。重灌与删文档都走这里，避免软删记录越积越多。
     *
     * @param docId 文档
     * @return 删除行数
     */
    int deleteByDocId(Long docId);

    /**
     * @param kbId 知识库
     * @return 该库切片总数
     */
    int countByKb(Long kbId);

    /**
     * 物理删除整个知识库的切片。删库时调用，此时已不需要保留任何切片。
     *
     * @param kbId 知识库
     * @return 删除行数
     */
    int deleteByKb(Long kbId);
}

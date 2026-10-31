package com.agenthub.rag.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.rag.domain.model.RagDocument;

import java.util.List;
import java.util.Optional;

/**
 * 文档仓储。所属域：rag。实现不得把 Entity 交回应用层。
 */
public interface RagDocumentRepository {

    /**
     * 分页查询某库下的未删除文档，可按标题模糊过滤。
     *
     * @param kbId    知识库
     * @param keyword 标题关键字，空则不过滤
     * @param current 页码，从 1 起
     * @param size    每页条数
     * @return 一页文档
     */
    PageResult<RagDocument> page(Long kbId, String keyword, long current, long size);

    /**
     * @param id 主键
     * @return 未删除文档
     */
    Optional<RagDocument> findById(Long id);

    /**
     * 同一知识库内是否已有同名且未删除的文档（重灌判断用）。
     *
     * @param kbId  知识库
     * @param title 标题
     * @return 已存在的文档
     */
    Optional<RagDocument> findByKbAndTitle(Long kbId, String title);

    /**
     * @param kbId 知识库
     * @return 该库下未删除文档总数，删除知识库前做提示
     */
    int countByKb(Long kbId);

    /**
     * @param doc        新文档
     * @param operatorId 上传人
     * @return 新主键
     */
    Long insert(RagDocument doc, Long operatorId);

    /**
     * 写状态与失败原因。成功时 errorMsg 传 null 以清空历史失败信息。
     *
     * @param id       主键
     * @param status   PENDING / RUNNING / READY / FAILED
     * @param chunkCnt 切片数，未知传 null 表示不改
     * @param errorMsg 失败原因，成功传 null
     */
    void updateStatus(Long id, String status, Integer chunkCnt, String errorMsg);

    /**
     * 落盘后回写原文件相对路径。insert 时尚无 docId 命名，必须另写，否则重试/重切读不到文件。
     *
     * @param id        文档主键
     * @param sourceUri {@code RagFileStorage#save} 返回的相对路径
     */
    void updateSourceUri(Long id, String sourceUri);

    /**
     * 重灌前递增代次。
     *
     * @param id 主键
     */
    void bumpVersion(Long id);

    /**
     * @param id 主键
     */
    void logicalDelete(Long id);

    /**
     * 逻辑删除整个知识库的文档。删库时调用，避免留下悬空行。
     *
     * @param kbId 知识库
     * @return 删除行数
     */
    int logicalDeleteByKb(Long kbId);
}

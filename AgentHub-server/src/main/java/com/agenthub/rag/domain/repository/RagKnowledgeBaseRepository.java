package com.agenthub.rag.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.rag.domain.model.RagKbQuery;
import com.agenthub.rag.domain.model.RagKnowledgeBase;

import java.util.Optional;

/**
 * 知识库仓储。所属域：rag。实现不得把 Entity 交回应用层。
 */
public interface RagKnowledgeBaseRepository {

    /**
     * @param query 页码、关键词、状态、可见性
     * @return 一页知识库
     */
    PageResult<RagKnowledgeBase> page(RagKbQuery query);

    /**
     * @param id 主键
     * @return 未删除的知识库
     */
    Optional<RagKnowledgeBase> findById(Long id);

    /**
     * 未删除范围内名称是否已被占用。
     *
     * @param name      名称（已去首尾空白）
     * @param excludeId 更新时排除自己；新建传 null
     * @return true 表示冲突
     */
    boolean existsByName(String name, Long excludeId);

    /**
     * @param kb         新知识库
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(RagKnowledgeBase kb, Long operatorId);

    /**
     * 改可编辑资料（名称、描述、可见性、rerank、检索与入库参数），不改初始化结果。
     *
     * @param kb         已存在的知识库
     * @param operatorId 更新人
     */
    void updateProfile(RagKnowledgeBase kb, Long operatorId);

    /**
     * 写建集结果：集合名、能力、状态、失败原因。成功时 error_msg 置空。
     *
     * @param id             主键
     * @param collectionName 集合名，失败可为 null
     * @param capability     DENSE_BM25 / DENSE_ONLY，失败可为 null
     * @param status         READY / INIT_FAILED
     * @param errorMsg       失败原因，成功传 null
     * @param operatorId     操作人
     */
    void updateInitResult(Long id, String collectionName, String capability,
                          String status, String errorMsg, Long operatorId);

    /**
     * @param id         主键
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);
}

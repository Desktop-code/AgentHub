package com.agenthub.rag.domain.repository;

import com.agenthub.rag.domain.model.RagIngestTask;

import java.util.Optional;

/**
 * 入库任务仓储。所属域：rag。
 * 一条文档只保留一条「当前」任务行；重试时复用该行并把 attempts +1。
 */
public interface RagIngestTaskRepository {

    /**
     * @param task       新任务
     * @param operatorId 触发人
     * @return 新主键
     */
    Long insert(RagIngestTask task, Long operatorId);

    /**
     * @param id 主键
     * @return 任务
     */
    Optional<RagIngestTask> findById(Long id);

    /**
     * @param docId 文档
     * @return 该文档当前任务
     */
    Optional<RagIngestTask> findByDocId(Long docId);

    /**
     * 标记进入 RUNNING，并累加 attempts、记录本轮开始时间、清空上次失败原因。
     *
     * @param id 主键
     */
    void markRunning(Long id);

    /**
     * 更新进度与阶段（RUNNING 期间高频，字段少所以直接 update）。
     *
     * @param id       主键
     * @param stage    阶段名
     * @param progress 0-100
     */
    void updateProgress(Long id, String stage, int progress);

    /**
     * 标记成功：status=SUCCESS、progress=100、清空 errorMsg、记录结束时间。
     *
     * @param id 主键
     */
    void markSuccess(Long id);

    /**
     * 标记失败：status=FAILED、记录 errorMsg 与结束时间。
     *
     * @param id       主键
     * @param errorMsg 失败原因（不含密钥）
     */
    void markFailed(Long id, String errorMsg);

    /**
     * 重试前把终态任务重置为 PENDING，并清空上一轮的阶段与进度。
     *
     * @param id 主键
     */
    void resetForRetry(Long id);

    /**
     * @param docId 文档
     */
    void deleteByDocId(Long docId);

    /**
     * 物理删除整个知识库的任务行。删库时调用。
     *
     * @param kbId 知识库
     * @return 删除行数
     */
    int deleteByKb(Long kbId);
}

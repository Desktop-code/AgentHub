package com.agenthub.eval.domain.repository;

import com.agenthub.eval.domain.model.EvalCase;

import java.util.List;
import java.util.Optional;

/**
 * 评测用例仓储。所属域：eval 的 domain。
 * <p>实现方：infra 的 {@code EvalCaseRepositoryImpl}。
 * <p>版本语义：历史版本行**只读**。仓储只提供按版本读取与「追加新版本行」，
 * 不提供原地更新（避免把已发布版本的用例改脏）。
 * 线程：无状态，可并发调用。
 */
public interface EvalCaseRepository {

    /**
     * 列出某套件某版本的用例，按 sort_order、id 升序。
     *
     * @param suiteId      套件
     * @param suiteVersion 版本
     * @return 用例列表，可能为空
     */
    List<EvalCase> listByVersion(Long suiteId, int suiteVersion);

    /**
     * 按版本内逻辑键取单条。
     *
     * @param suiteId      套件
     * @param suiteVersion 版本
     * @param caseKey      用例键
     * @return 用例
     */
    Optional<EvalCase> findByKey(Long suiteId, int suiteVersion, String caseKey);

    /**
     * 批量写入某一版本的用例。
     *
     * @param cases      用例行（suiteId / suiteVersion 已填好）
     * @param operatorId 操作人
     * @return 写入条数
     */
    int insertBatch(List<EvalCase> cases, Long operatorId);

    /**
     * 统计某套件某版本的用例数。
     *
     * @param suiteId      套件
     * @param suiteVersion 版本
     * @return 条数
     */
    long countByVersion(Long suiteId, int suiteVersion);

    /**
     * 逻辑删除套件下所有版本的用例（套件删除时墓碑化）。
     *
     * @param suiteId    套件
     * @param operatorId 操作人
     * @return 受影响行数
     */
    int logicalDeleteBySuite(Long suiteId, Long operatorId);
}

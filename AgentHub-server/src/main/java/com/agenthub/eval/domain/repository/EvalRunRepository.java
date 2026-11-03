package com.agenthub.eval.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalRunStatus;

import java.util.Optional;

/**
 * 评测运行仓储。所属域：eval 的 domain。
 * <p>实现方：infra 的 {@code EvalRunRepositoryImpl}。Entity 不出 infra。
 * <p>状态写入口径：本接口**不提供**「只改状态」的入口，
 * 只有 {@link #updateState} 一次性把状态 + 计数器 + 门禁结果 + 时间戳写成一体，
 * 迫使调用方先过 {@code EvalRunStateMachine}（域内唯一状态出口）。
 * <p>租户隔离：按 id 查不带 tenant 条件（供应用层区分 404 与越权）；写操作一律带 tenant_id。
 * 线程：实现须无状态。
 */
public interface EvalRunRepository {

    /**
     * 新建跑批。
     *
     * @param run        运行行（tenantId / suiteId / suiteVersion / agentId / status 必填）
     * @param operatorId 操作人
     * @return 新主键
     */
    Long insert(EvalRun run, Long operatorId);

    /**
     * 按主键查询（不带租户条件，调用方自行比对 tenantId）。
     *
     * @param id 主键
     * @return 未逻辑删的运行行
     */
    Optional<EvalRun> findById(Long id);

    /**
     * 本租户跑批分页；新在前。筛选均可空。
     *
     * @param tenantId 租户
     * @param suiteId  套件，可空
     * @param agentId  Agent，可空
     * @param status   状态名，可空
     * @param current  页码
     * @param size     页大小
     * @return 分页
     */
    PageResult<EvalRun> page(long tenantId, Long suiteId, Long agentId, String status,
                             long current, long size);

    /**
     * 把状态机算好的结果落库：status / gate_passed / total_cases / passed / failed / errored /
     * skipped / started_time / finished_time / error_message / agent_snapshot_hash /
     * report_json / report_path。
     * <p>带 tenant_id 条件做二次保险，避免应用层判空漏掉时被越权改。
     *
     * @param run        已由状态机改写过的运行行
     * @param operatorId 操作人
     * @return 受影响行数；0 表示 id/租户不匹配
     */
    int updateState(EvalRun run, Long operatorId);

    /**
     * CAS 写状态：仅当库内 status 仍等于 {@code expected} 时才落 {@code run} 的状态机结果。
     * <p>用途：execute 的 PENDING→RUNNING 开跑闸，防双点 execute 双开 Trial。
     *
     * @param run        已由状态机改写过的运行行（含目标 status）
     * @param expected   期望的库内旧状态
     * @param operatorId 操作人
     * @return 受影响行数；0 表示并发抢跑失败或 id/租户不匹配
     */
    int casUpdateState(EvalRun run, EvalRunStatus expected, Long operatorId);
}

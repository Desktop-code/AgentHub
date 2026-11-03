package com.agenthub.eval.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalVerdict;

import java.util.List;
import java.util.Optional;

/**
 * 评测结果仓储。所属域：eval 的 domain。
 * <p>实现方：infra 的 {@code EvalResultRepositoryImpl}。
 * <p>为什么单独给出 {@link #markUnfinished} 而不是逐行 update：
 * 取消收尾要把「所有未出判定」的行一次性打成 SKIPPED，逐行 update 会发 N 条语句；
 * 这里用一条 {@code WHERE verdict IS NULL} 的 UPDATE 完成，语义与域内口径完全对齐。
 * <p>线程：实现须无状态。
 */
public interface EvalResultRepository {

    /**
     * 建跑时批量插入占位结果行（verdict 与评分字段均为空）。
     *
     * @param results    结果行（runId / tenantId / caseKey / caseId / sortOrder 必填）
     * @param operatorId 操作人；本表无审计列，仅用于日志追溯
     * @return 写入条数
     */
    int insertBatch(List<EvalResult> results, Long operatorId);

    /**
     * 按主键查（不带租户；调用方比对 tenantId）。
     *
     * @param id 主键
     * @return 结果行
     */
    Optional<EvalResult> findById(Long id);

    /**
     * 取某 run 的全部结果行（取消收尾与计数器重算要用全量）。
     *
     * @param runId 跑批
     * @return 结果行，按 sort_order、id 升序；可能为空
     */
    List<EvalResult> listByRun(Long runId);

    /**
     * 本租户按判定分页（坏案列表）；新在前。
     *
     * @param tenantId      租户
     * @param verdicts      判定集合（如 FAIL，或 FAIL+ERROR）
     * @param current       页码
     * @param size          页大小
     * @return 分页
     */
    PageResult<EvalResult> pageByVerdicts(long tenantId, List<EvalVerdict> verdicts,
                                          long current, long size);

    /**
     * 分页取某 run 的结果行。
     *
     * @param runId    跑批
     * @param tenantId 租户（隔离条件）
     * @param current  页码，从 1 开始
     * @param size     每页条数，上限 100
     * @return 分页结果
     */
    PageResult<EvalResult> pageByRun(Long runId, long tenantId, long current, long size);

    /**
     * 把该 run 下所有尚未出判定（verdict IS NULL）的行置为指定判定，并写结束时间。
     * <p>当前唯一用途是取消收尾写 SKIPPED；传入非 SKIPPED 的判定会被实现拒绝，
     * 避免把「跳过」这个语义被别处滥用成「批量判失败」。
     *
     * @param runId      跑批
     * @param tenantId   租户（隔离条件）
     * @param verdict    目标判定，只允许 SKIPPED
     * @param operatorId 操作人；本表无审计列，仅用于日志追溯
     * @return 受影响行数
     */
    int markUnfinished(Long runId, long tenantId, EvalVerdict verdict, Long operatorId);

    /**
     * 回写单 case 产物与判定（刀2b Runner 用）。
     * <p>只更新定位到的一行（id + run_id + tenant_id），避免误伤同 run 其它 case。
     *
     * @param result 已填好 verdict / session / artifact 等字段的结果行
     * @return 受影响行数；0 表示行不存在或租户不匹配
     */
    int updateOutcome(EvalResult result);
}

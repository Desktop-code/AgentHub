package com.agenthub.eval.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.eval.domain.model.EvalSuite;
import com.agenthub.eval.domain.model.EvalSuiteQuery;

import java.util.Optional;

/**
 * 评测套件仓储。所属域：eval 的 domain。
 * <p>实现方：infra 的 {@code EvalSuiteRepositoryImpl}。Entity 不出 infra。
 * <p>租户隔离责任：所有读写都必须带 tenantId 条件（或在应用层校验后调用），仓储不提供「无租户」查询。
 * 线程：无状态，可被并发调用。
 */
public interface EvalSuiteRepository {

    /**
     * 分页查询本租户套件；{@code includePlatform=true} 时并集 tenant_id=0 的平台样例（只读）。
     *
     * @param query 分页与筛选条件，tenantId 必填
     * @return 分页结果
     */
    PageResult<EvalSuite> page(EvalSuiteQuery query);

    /**
     * 按主键查询，**不做租户过滤**：调用方必须自行比对 tenantId（用于给出 404 而不是越权 403）。
     *
     * @param id 主键
     * @return 未删除的套件
     */
    Optional<EvalSuite> findById(Long id);

    /**
     * 租户内按逻辑键查重。
     *
     * @param tenantId 租户
     * @param suiteKey 逻辑键
     * @return 未删除的套件
     */
    Optional<EvalSuite> findByTenantAndKey(long tenantId, String suiteKey);

    /**
     * 统计租户内某逻辑键的占用数（含已逻辑删的行）。
     * 物理 UK 是 (tenant_id, suite_key)，软删行仍占键位，创建前用它给出可读的冲突提示。
     *
     * @param tenantId 租户
     * @param suiteKey 逻辑键
     * @return 占用行数
     */
    long countByTenantAndKeyIncludingDeleted(long tenantId, String suiteKey);

    /**
     * 新建套件。
     *
     * @param suite      套件（tenantId / suiteKey / name / currentVersion 必填）
     * @param operatorId 操作人
     * @return 新主键
     */
    Long insert(EvalSuite suite, Long operatorId);

    /**
     * 更新套件元数据（name / description / default_config_json / enabled）。
     * 不动 suite_key 与 current_version。
     *
     * @param suite      含 id 与新值的套件
     * @param operatorId 操作人
     */
    void updateMeta(EvalSuite suite, Long operatorId);

    /**
     * 乐观推进当前版本号（CAS）：仅当库里仍是 {@code expectedVersion} 时才改成 {@code newVersion}。
     * <p>为什么用 CAS：导入是先算 {@code current+1} 再写用例行，
     * 两个并发导入会算出同一个新版本号，靠唯一键报错既难懂也浪费一次写；
     * 这里先抢版本号，抢不到就让调用方提示重试。版本号只增不减。
     *
     * @param id              主键
     * @param tenantId        租户（防越权写的二次保险）
     * @param expectedVersion 期望的当前库内版本
     * @param newVersion      目标版本
     * @param operatorId      操作人
     * @return 受影响行数；0 表示版本已被并发变更或 id/租户不匹配
     */
    int bumpVersion(Long id, long tenantId, int expectedVersion, int newVersion, Long operatorId);

    /**
     * 逻辑删除套件（用例由应用层另行墓碑化）。
     *
     * @param id         主键
     * @param tenantId   租户
     * @param operatorId 操作人
     * @return 受影响行数；0 表示 id/租户不匹配
     */
    int logicalDelete(Long id, long tenantId, Long operatorId);
}

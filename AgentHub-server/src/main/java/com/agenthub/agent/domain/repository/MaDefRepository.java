package com.agenthub.agent.domain.repository;

import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.model.MaDefQuery;
import com.agenthub.common.api.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多智能体方案仓储。所属域：agent。实现不得把 Entity 交回应用层。
 *
 * <p>租户纪律：列表与写操作强制带 tenant_id；按 id 查询故意不带租户，
 * 由应用层区分 404 与「跨租户越权」。
 */
public interface MaDefRepository {

    /**
     * @param query 分页、关键词、启停、模板、是否绑根与租户
     * @return 一页方案定义
     */
    PageResult<MaDef> page(MaDefQuery query);

    /**
     * @param id 主键
     * @return 未删除方案；不存在为空
     */
    Optional<MaDef> findById(Long id);

    /**
     * @param tenantId 当前租户
     * @param code     方案编码
     * @return 同租户同编码方案；不存在为空
     */
    Optional<MaDef> findByTenantAndCode(long tenantId, String code);

    /**
     * 查同一绑根下已启用的其它方案；用于「同一 root 最多一条启用中」校验。
     *
     * @param tenantId    当前租户
     * @param rootAgentId 绑定的 ROUTER 主键
     * @param excludeId   更新时排除自身；新建传 null
     * @return 命中的其它启用方案
     */
    Optional<MaDef> findEnabledByRoot(long tenantId, long rootAgentId, Long excludeId);

    /**
     * 绑定根 → 启用中的方案。用于旧「编排」Tab 写回时反查应该镜像到哪个 ma_def。
     *
     * @param tenantId    当前租户
     * @param rootAgentId 绑定的 ROUTER 主键
     * @return 启用中的方案；不存在为空
     */
    Optional<MaDef> findEnabledByRoot(long tenantId, long rootAgentId);

    /**
     * @param maId 方案主键
     * @return 候选列表，sort_no 小的在前
     */
    List<MaCandidate> listCandidates(Long maId);

    /**
     * 列表「候选启用数」列：按整页方案 id 一次 group by，避免逐行 count。
     *
     * @param maIds 方案主键集合，可空
     * @return maId → 启用中的候选数；无候选的 id 不出现在结果里
     */
    Map<Long, Integer> countEnabledCandidates(Collection<Long> maIds);

    /**
     * @param def        新方案
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(MaDef def, Long operatorId);

    /**
     * 改方案基本信息与配置；version 由实现自增。
     *
     * @param def        已存在的方案（id / tenantId 必填）
     * @param operatorId 更新人
     */
    void update(MaDef def, Long operatorId);

    /**
     * 只改启停；version 不递增（启停不是配置变更）。
     *
     * @param id         主键
     * @param tenantId   当前租户
     * @param enabled    1 / 0
     * @param operatorId 操作人
     * @return 影响行数；0 表示不存在或跨租户
     */
    int updateEnabled(Long id, long tenantId, int enabled, Long operatorId);

    /**
     * 物理删除方案与全部候选；删除动作由应用层写操作日志留痕。
     *
     * @param id         主键
     * @param tenantId   当前租户
     * @return 影响行数；0 表示不存在或跨租户
     */
    int delete(Long id, long tenantId);

    /**
     * 全量替换候选：先物理删除旧行再插入新行。候选是方案从属子资源，不做逻辑删除。
     *
     * @param maId       方案主键
     * @param candidates 新候选；空列表表示清空
     */
    void replaceCandidates(Long maId, List<MaCandidate> candidates);

    /**
     * @param maId 方案主键
     */
    void deleteCandidates(Long maId);
}

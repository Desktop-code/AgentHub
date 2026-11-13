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
     * 批量按主键取方案（刀5：路由池里的 MA 候选要回填方案名与说明）。
     *
     * <p>不带租户条件：调用方必须自己按 {@code tenantId} 复核，避免跨租户回显。
     *
     * @param ids 方案主键集合；空集合直接返回空列表
     * @return 命中的方案；不存在的 id 不出现
     */
    List<MaDef> findByIds(Collection<Long> ids);

    /**
     * 本租户全部（未删除）方案，更新时间倒序。
     *
     * <p>用途：默认接待「添加方案」下拉的候选来源；启用 / 默认位 / 叶子 / 说明等门闩
     * 由应用层按与保存侧同一套规则再筛，仓储不做业务判断。
     *
     * @param tenantId 当前租户
     * @return 方案列表；没有时为空
     */
    List<MaDef> listByTenant(long tenantId);

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
     * 查平台默认接待方案（可能已停用，便于调用方区分「未配置」与「已停用」）。
     *
     * @param tenantId 当前租户
     * @return 标记为默认接待的方案；未配置为空
     */
    Optional<MaDef> findReceptionDefault(long tenantId);

    /**
     * 本租户所有方案已占用的候选 Agent 主键（跨方案去重）。
     *
     * <p>用途：默认接待的「添加子 Agent」下拉要排除已被任一方案拉走的 Agent，
     * 避免同一个 Agent 同时挂在两条入口上被重复调度。
     *
     * @param tenantId 当前租户
     * @return 候选 Agent 主键集合；没有候选时为空
     */
    List<Long> listCandidateAgentIds(long tenantId);

    /**
     * 设置 / 取消平台默认接待方案。
     *
     * <p>设置时先清掉本租户其它默认位再落新值，唯一索引作为最终兜底；
     * 取消时只清目标行。调用方负责事务边界。
     *
     * @param id         方案主键
     * @param tenantId   当前租户
     * @param on         true=设为默认，false=取消默认
     * @param operatorId 操作人
     * @return 命中并更新的方案行数（0 表示不存在或跨租户）
     */
    int updateReceptionDefault(Long id, long tenantId, boolean on, Long operatorId);

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

    /**
     * 追加一条候选（保留既有候选 id，供简易配置页增删改排序使用）。
     *
     * @param candidate 候选；maId 必填
     * @return 新主键
     */
    Long insertCandidate(MaCandidate candidate);

    /**
     * 改候选的意图说明 / 排序 / 启停；方案归属作为条件，避免跨方案改行。
     *
     * @param id         候选主键
     * @param maId       方案主键
     * @param intentDesc 意图说明；null 表示不改
     * @param sortNo     排序；null 表示不改
     * @param enabled    启停；null 表示不改
     * @return 影响行数；0 表示不存在或不属于该方案
     */
    int updateCandidate(Long id, Long maId, String intentDesc, Integer sortNo, Integer enabled);

    /**
     * 删除一条候选。
     *
     * @param id   候选主键
     * @param maId 方案主键
     * @return 影响行数；0 表示不存在或不属于该方案
     */
    int deleteCandidate(Long id, Long maId);
}

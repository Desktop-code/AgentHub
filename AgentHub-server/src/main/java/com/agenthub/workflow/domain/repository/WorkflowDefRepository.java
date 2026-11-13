package com.agenthub.workflow.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowDefQuery;

import java.util.List;
import java.util.Optional;

/**
 * 工作流定义仓储。所属域：workflow 的 domain。
 * <p>实现不得把 Entity 交回应用层；按 id 查询故意不带租户，由应用层区分 404 与越权，
 * 写操作必须带 tenant_id 条件防止漏判。
 */
public interface WorkflowDefRepository {

    /**
     * @param query 分页、关键词、状态与租户
     * @return 一页定义
     */
    PageResult<WorkflowDef> page(WorkflowDefQuery query);

    /**
     * @param id 主键
     * @return 未删除的定义；不存在为空
     */
    Optional<WorkflowDef> findById(Long id);

    /**
     * @param tenantId 当前租户
     * @param code     逻辑编码
     * @return 未删除且租户匹配的定义
     */
    Optional<WorkflowDef> findByTenantAndCode(long tenantId, String code);

    /**
     * @param def        新定义
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(WorkflowDef def, Long operatorId);

    /**
     * 改名称与说明；code 与状态不允许经此方法修改。
     *
     * @param def        已存在的定义（id / tenantId 必填）
     * @param operatorId 更新人
     */
    void updateMeta(WorkflowDef def, Long operatorId);

    /**
     * 发布后推进生命周期与默认发布指针。
     *
     * @param id         主键
     * @param tenantId   当前租户
     * @param releaseId  新发布版本 id
     * @param operatorId 操作人
     */
    void markPublished(Long id, long tenantId, Long releaseId, Long operatorId);

    /**
     * 仅刷新更新人与更新时间，用于草稿保存后让列表排序/审计可见。
     *
     * @param id         主键
     * @param tenantId   当前租户
     * @param operatorId 操作人
     */
    void touch(Long id, long tenantId, Long operatorId);

    /**
     * 逻辑删除定义；草稿与发布版本不物理删除，保留历史快照与运行追溯。
     *
     * @param id         主键
     * @param tenantId   当前租户
     * @param operatorId 操作人
     * @return 影响行数；0 表示不存在或跨租户
     */
    int softDelete(Long id, long tenantId, Long operatorId);

    /**
     * 可作为路由目标的工作流：未删除、已发布（HAS_RELEASE 且有默认发布指针）、说明非空。
     *
     * <p>只给「默认接待 / 方案路由池」的下拉与装载用，不代表全部工作流；
     * 草稿-only、无默认发布、空说明的定义一律不返回（它们不能被路由到）。
     *
     * @param tenantId 当前租户
     * @param limit    条数上限（实现方钳制）
     * @return 按 id 倒序的定义列表；没有为空列表
     */
    List<WorkflowDef> listRoutable(long tenantId, int limit);
}

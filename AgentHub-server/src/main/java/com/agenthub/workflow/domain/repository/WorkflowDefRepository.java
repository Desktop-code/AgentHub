package com.agenthub.workflow.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowDefQuery;

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
}

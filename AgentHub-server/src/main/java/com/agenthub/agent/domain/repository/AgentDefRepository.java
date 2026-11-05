package com.agenthub.agent.domain.repository;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentQuery;
import com.agenthub.common.api.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Agent 定义仓储。所属域：agent。实现不得把 Entity 交回应用层。
 */
public interface AgentDefRepository {

    /**
     * @param query 页码、关键词、模式和启停
     * @return 一页定义
     */
    PageResult<AgentDef> page(AgentQuery query);

    /**
     * @param id 主键
     * @return 未删除的定义
     */
    Optional<AgentDef> findById(Long id);

    /**
     * 按主键解析定义，含已逻辑删除行。
     * 仅供用量展示等历史引用拼名；业务校验与下拉仍用 {@link #findById}。
     *
     * @param id Agent 主键
     * @return 存在则带回，deleted=1 表示已软删
     */
    Optional<AgentDef> findByIdIncludingDeleted(Long id);

    /**
     * 批量取未删除定义；列表补绑定根名称、候选子展示用，避免逐条查询 N+1。
     *
     * @param ids 主键集合，可空
     * @return 命中的定义；顺序不保证
     */
    List<AgentDef> findByIds(Collection<Long> ids);

    /**
     * 未删除范围内编码是否已被占用。
     *
     * @param code      业务编码
     * @param excludeId 更新时排除自己；新建传 null
     * @return true 表示冲突
     */
    boolean existsByCode(String code, Long excludeId);

    /**
     * @param agent      新定义
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(AgentDef agent, Long operatorId);

    /**
     * 改基本信息，不改启停。
     *
     * @param agent      已存在的定义
     * @param operatorId 更新人
     */
    void updateProfile(AgentDef agent, Long operatorId);

    /**
     * 只覆盖运行配置 JSON；多智能体方案写入后镜像回绑定根时使用。
     * <p>不碰基本信息与启停，避免把镜像写成一次完整保存。
     *
     * @param id                Agent 主键
     * @param runtimeConfigJson 已归一化的运行配置 JSON
     * @param operatorId        操作人
     */
    void updateRuntimeConfig(Long id, String runtimeConfigJson, Long operatorId);

    /**
     * @param id         主键
     * @param status     1 正常，0 停用
     * @param operatorId 更新人
     */
    void updateStatus(Long id, int status, Long operatorId);

    /**
     * @param id         主键
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);
}

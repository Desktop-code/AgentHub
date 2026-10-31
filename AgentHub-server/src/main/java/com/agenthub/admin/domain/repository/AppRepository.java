package com.agenthub.admin.domain.repository;

import com.agenthub.admin.domain.model.App;
import com.agenthub.admin.domain.model.AppQuery;
import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.common.api.PageResult;

import java.util.Collection;
import java.util.Optional;

/**
 * 调用方应用仓储。所属域：admin。不返回密钥哈希。
 */
public interface AppRepository {

    /**
     * @param query 分页条件
     * @param scope 数据范围
     * @return 一页应用，含已授权的 agent id
     */
    PageResult<App> page(AppQuery query, DataScopeSpec scope);

    /**
     * @param id 应用 id
     * @return 未删除应用
     */
    Optional<App> findById(Long id);

    /**
     * @param app           应用资料
     * @param secretHash    密钥哈希
     * @param operatorId    创建人
     * @return 主键
     */
    Long insert(App app, String secretHash, Long operatorId);

    /**
     * 只改名称、部门、状态和备注。
     *
     * @param app        含 id 的应用
     * @param operatorId 操作者
     */
    void updateProfile(App app, Long operatorId);

    /**
     * @param id         应用 id
     * @param secretHash 新哈希
     * @param operatorId 操作者
     */
    void updateSecret(Long id, String secretHash, Long operatorId);

    /**
     * @param id         应用 id
     * @param operatorId 操作者
     */
    void logicDelete(Long id, Long operatorId);

    /**
     * 物理替换授权。agent id 不校验是否存在，本期没有 Agent 表。
     *
     * @param appId    应用 id
     * @param agentIds 授权 id，空则清空
     */
    void replaceAgents(Long appId, Collection<Long> agentIds);

    /**
     * app_key 是否占用，包含已删除行。
     *
     * @param appKey 对外 appId
     * @return 已占用时为 true
     */
    boolean appKeyTaken(String appKey);
}

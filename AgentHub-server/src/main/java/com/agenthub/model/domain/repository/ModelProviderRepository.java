package com.agenthub.model.domain.repository;

import com.agenthub.model.domain.model.ModelProvider;

import java.util.List;
import java.util.Optional;

/**
 * 供应商仓储。所属域：model。实现在 infra，应用服务只依赖本接口。
 */
public interface ModelProviderRepository {

    /**
     * @return 未删除的供应商，新的在前
     */
    List<ModelProvider> listAll();

    /**
     * @param id 供应商 id
     * @return 未删除的记录
     */
    Optional<ModelProvider> findById(Long id);

    /**
     * 名称是否已被未删除供应商占用。
     *
     * @param name      名称（精确匹配）
     * @param excludeId 更新时排除自身；新建传 null
     * @return true 表示冲突
     */
    boolean nameExists(String name, Long excludeId);

    /**
     * @param provider   待插入的资料，不含 id
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(ModelProvider provider, Long operatorId);

    /**
     * @param provider   已存在的资料
     * @param operatorId 更新人
     */
    void update(ModelProvider provider, Long operatorId);

    /**
     * 逻辑删除。调用前须已确认没有实例引用。
     *
     * @param id         供应商 id
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);
}

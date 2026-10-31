package com.agenthub.tool.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.tool.domain.model.ItemQuery;
import com.agenthub.tool.domain.model.ToolItem;

import java.util.List;
import java.util.Optional;

/**
 * 工具项仓储。所属域：tool。同名判断只看未删除行。
 */
public interface ToolItemRepository {

    /**
     * @param query 源、类型解析出的 id、启停和关键词
     * @return 一页工具项
     */
    PageResult<ToolItem> page(ItemQuery query);

    /**
     * @param id 工具项 id
     * @return 未删除的项
     */
    Optional<ToolItem> findById(Long id);

    /**
     * 同步和重名检查用。多行时取最早一条，避免脏数据把 selectOne 打爆。
     *
     * @param sourceId 工具源 id
     * @param name     调用名
     * @return 未删除的同名项
     */
    Optional<ToolItem> findBySourceAndName(Long sourceId, String name);

    /**
     * @param sourceId 工具源 id
     * @return 该源下未删除的全部工具项
     */
    List<ToolItem> listBySourceId(Long sourceId);

    /**
     * @param item       新项，status 由调用方决定
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(ToolItem item, Long operatorId);

    /**
     * 仅当 input_schema_json 为 NULL 或空白时写入。给 BUILTIN 启动回填用，不覆盖已有 JSON。
     *
     * @param id         工具项 id
     * @param schemaJson Java 生成的紧凑 JSON Schema
     * @return 本次是否写上；已有内容或入参空白时为 false
     */
    boolean fillInputSchemaIfBlank(Long id, String schemaJson);

    /**
     * 同步或手工编辑元数据。不改 name、sourceId 和 status。
     *
     * @param item       已存在的项
     * @param operatorId 更新人
     */
    void updateMetadata(ToolItem item, Long operatorId);

    /**
     * @param id         工具项 id
     * @param status     1 进入白名单，0 移出
     * @param operatorId 操作人
     */
    void updateStatus(Long id, int status, Long operatorId);

    /**
     * 批量启停某源下全部未删除工具项（MCP 源内「全部启用 / 全部停用」）。
     * 只按 source_id 圈定，不碰其它源与已删除行。
     *
     * @param sourceId   工具源 id
     * @param status     1 进入白名单，0 移出
     * @param operatorId 操作人
     * @return 实际更新的行数
     */
    int updateStatusBySourceId(Long sourceId, int status, Long operatorId);

    /**
     * @param id         工具项 id
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);

    /**
     * 删除 MCP 源时连带逻辑删除其下工具项。
     *
     * @param sourceId   工具源 id
     * @param operatorId 操作人
     */
    void logicalDeleteBySourceId(Long sourceId, Long operatorId);
}

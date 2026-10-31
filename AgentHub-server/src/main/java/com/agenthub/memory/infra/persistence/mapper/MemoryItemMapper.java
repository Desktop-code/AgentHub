package com.agenthub.memory.infra.persistence.mapper;

import com.agenthub.memory.domain.model.MemoryQuery;
import com.agenthub.memory.infra.persistence.entity.MemoryItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * memory_item 映射。所属域：memory。
 * 已删除分页走 XML（绕过 TableLogic），其余用 BaseMapper。
 */
@Mapper
public interface MemoryItemMapper extends BaseMapper<MemoryItemEntity> {

    /**
     * 统计逻辑删除行，条件与控制台筛选一致。
     *
     * @param query 筛选（含 agentId / scope 等）
     * @return 条数
     */
    long countDeleted(@Param("q") MemoryQuery query);

    /**
     * 分页取逻辑删除行。
     *
     * @param query  筛选
     * @param offset 偏移
     * @param size   每页条数
     * @return 当前页
     */
    List<MemoryItemEntity> pageDeleted(@Param("q") MemoryQuery query,
                                       @Param("offset") long offset,
                                       @Param("size") long size);
}

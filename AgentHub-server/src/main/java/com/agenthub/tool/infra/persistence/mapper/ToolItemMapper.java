package com.agenthub.tool.infra.persistence.mapper;

import com.agenthub.tool.infra.persistence.entity.ToolItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * tool_item 映射。所属域：tool。
 */
@Mapper
public interface ToolItemMapper extends BaseMapper<ToolItemEntity> {
}

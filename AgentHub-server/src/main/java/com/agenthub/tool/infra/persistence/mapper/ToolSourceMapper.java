package com.agenthub.tool.infra.persistence.mapper;

import com.agenthub.tool.infra.persistence.entity.ToolSourceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * tool_source 映射。所属域：tool。
 */
@Mapper
public interface ToolSourceMapper extends BaseMapper<ToolSourceEntity> {
}

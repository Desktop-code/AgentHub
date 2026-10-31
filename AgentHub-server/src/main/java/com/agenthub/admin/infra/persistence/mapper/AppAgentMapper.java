package com.agenthub.admin.infra.persistence.mapper;

import com.agenthub.admin.infra.persistence.entity.SysAppAgentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_app_agent 映射。所属域：admin。
 */
@Mapper
public interface AppAgentMapper extends BaseMapper<SysAppAgentEntity> {
}

package com.agenthub.admin.infra.persistence.mapper;

import com.agenthub.admin.infra.persistence.entity.SysMenuEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_menu 映射。所属域：admin。简单条件用 Wrapper，不另写 XML。
 */
@Mapper
public interface MenuMapper extends BaseMapper<SysMenuEntity> {
}

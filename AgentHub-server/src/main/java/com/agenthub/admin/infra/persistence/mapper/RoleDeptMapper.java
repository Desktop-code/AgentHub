package com.agenthub.admin.infra.persistence.mapper;

import com.agenthub.admin.infra.persistence.entity.SysRoleDeptEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_role_dept 映射。所属域：admin。
 */
@Mapper
public interface RoleDeptMapper extends BaseMapper<SysRoleDeptEntity> {
}

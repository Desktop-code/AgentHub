package com.agenthub.admin.infra.persistence.mapper;

import com.agenthub.admin.infra.persistence.entity.SysRoleMenuEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_role_menu 映射。所属域：admin。
 */
@Mapper
public interface RoleMenuMapper extends BaseMapper<SysRoleMenuEntity> {
}

package com.agenthub.admin.infra.persistence.mapper;

import com.agenthub.admin.infra.persistence.entity.SysUserRoleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_user_role 映射。所属域：admin。按条件删除，不使用 deleteById（联合主键）。
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<SysUserRoleEntity> {
}

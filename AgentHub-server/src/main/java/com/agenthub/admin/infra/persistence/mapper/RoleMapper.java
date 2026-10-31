package com.agenthub.admin.infra.persistence.mapper;

import com.agenthub.admin.infra.persistence.entity.SysRoleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * sys_role 映射。所属域：admin。
 */
@Mapper
public interface RoleMapper extends BaseMapper<SysRoleEntity> {

    /**
     * 含已删除行，对齐不含 deleted 的唯一键。
     *
     * @param code 角色编码
     * @return 行数
     */
    long countByCode(@Param("code") String code);

    /**
     * 角色名称查重，只看未删除行（名称无唯一索引，软删后允许复用）。
     *
     * @param name      角色名称
     * @param excludeId 修改时排除自身，新建传 null
     * @return 命中行数，大于 0 表示已占用
     */
    long countByName(@Param("name") String name, @Param("excludeId") Long excludeId);
}

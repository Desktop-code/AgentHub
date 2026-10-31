package com.agenthub.admin.infra.persistence.mapper;

import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.infra.persistence.entity.SysUserEntity;
import com.agenthub.admin.infra.persistence.entity.UserRoleRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * sys_user 映射。所属域：admin。自定义 SQL 见同包 XML，逻辑删除条件手写。
 */
@Mapper
public interface UserMapper extends BaseMapper<SysUserEntity> {

    /**
     * 按数据范围分页。username 由调用方转义。
     *
     * @param page     分页参数，插件会追加 limit
     * @param username 登录名片段，可空
     * @param status   状态，可空
     * @param deptId   部门，可空
     * @param scope    数据范围
     * @return 含部门名称和 path 的一页
     */
    IPage<SysUserEntity> selectPageByScope(Page<SysUserEntity> page,
                                            @Param("username") String username,
                                            @Param("status") Integer status,
                                            @Param("deptId") Long deptId,
                                            @Param("scope") DataScopeSpec scope);

    /**
     * 不带 deleted 条件，用来判断唯一键是否仍被占用。
     *
     * @param username 登录名
     * @return 行数
     */
    long countByUsername(@Param("username") String username);

    /**
     * @param userId 用户 id
     * @return 该用户是否为启用中的 admin 角色持有者
     */
    long countActiveAdmin(@Param("userId") Long userId);

    /**
     * @param userId 排除的用户
     * @return 其他启用管理员人数
     */
    long countOtherActiveAdmins(@Param("userId") Long userId);

    /**
     * @param userIds 用户 id
     * @return 角色 id 与名称
     */
    List<UserRoleRow> selectRolesByUserIds(@Param("userIds") Collection<Long> userIds);
}

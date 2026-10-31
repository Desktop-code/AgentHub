package com.agenthub.admin.domain.repository;

import com.agenthub.admin.domain.model.Role;
import com.agenthub.admin.domain.model.RoleQuery;
import com.agenthub.common.api.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 角色仓储。所属域：admin。
 */
public interface RoleRepository {

    /**
     * @param id 角色 id
     * @return 未删除角色，不含菜单和自定义部门
     */
    Optional<Role> findById(Long id);

    /**
     * @param code 角色编码
     * @return 未删除角色
     */
    Optional<Role> findByCode(String code);

    /**
     * @param query 分页条件
     * @return 一页角色
     */
    PageResult<Role> page(RoleQuery query);

    /**
     * @param userId 用户 id
     * @return 该用户关联的角色，含停用角色，便于上层自行跳过
     */
    List<Role> findByUserId(Long userId);

    /**
     * @param role 新角色
     * @return 主键
     */
    Long insert(Role role);

    /**
     * @param role 含 id 的角色资料
     */
    void update(Role role);

    /**
     * @param id         角色 id
     * @param operatorId 操作者
     */
    void logicDelete(Long id, Long operatorId);

    /**
     * @param roleId 角色 id
     * @return 已分配菜单 id
     */
    List<Long> findMenuIds(Long roleId);

    /**
     * @param roleId  角色 id
     * @param menuIds 新菜单 id，空则清空
     */
    void replaceMenus(Long roleId, Collection<Long> menuIds);

    /**
     * @param roleId 角色 id
     * @return 自定义部门 id
     */
    List<Long> findCustomDeptIds(Long roleId);

    /**
     * @param roleId  角色 id
     * @param deptIds 自定义部门，空则清空
     */
    void replaceCustomDepts(Long roleId, Collection<Long> deptIds);

    /**
     * 编码是否被占用，包含已逻辑删除的行。
     *
     * @param code 角色编码
     * @return 已占用时为 true
     */
    boolean codeTaken(String code);

    /**
     * 名称是否被占用，只看未删除行；新建/修改都要校验。
     *
     * @param name      角色名称
     * @param excludeId 修改时排除自身，新建传 null
     * @return 已占用时为 true
     */
    boolean nameTaken(String name, Long excludeId);
}

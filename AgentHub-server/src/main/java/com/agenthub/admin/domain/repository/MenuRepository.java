package com.agenthub.admin.domain.repository;

import com.agenthub.admin.domain.model.Menu;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 菜单仓储。所属域：admin。
 */
public interface MenuRepository {

    /**
     * @return 全部未删除菜单，含停用和按钮
     */
    List<Menu> listAll();

    /**
     * 导航和权限加载使用：只要启用中的菜单。
     *
     * @return 未删除且 status=1 的菜单
     */
    List<Menu> listEnabled();

    /**
     * @param roleIds 角色 id
     * @return 这些角色勾选且仍启用的菜单
     */
    List<Menu> listEnabledByRoleIds(Collection<Long> roleIds);

    /**
     * @return 启用菜单上的非空权限码，给超管放行使用
     */
    List<String> listEnabledPermissionCodes();

    /**
     * @param id 菜单 id
     * @return 未删除菜单
     */
    Optional<Menu> findById(Long id);

    /**
     * @param menu 新菜单
     * @return 主键
     */
    Long insert(Menu menu);

    /**
     * @param menu 含 id 的菜单
     */
    void update(Menu menu);

    /**
     * @param id         菜单 id
     * @param operatorId 操作者
     */
    void logicDelete(Long id, Long operatorId);

    /**
     * @param id 菜单 id
     * @return 仍有未删除子节点时为 true
     */
    boolean hasChildren(Long id);
}

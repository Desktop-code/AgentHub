package com.agenthub.admin.infra.persistence;

import com.agenthub.admin.domain.model.Menu;
import com.agenthub.admin.domain.repository.MenuRepository;
import com.agenthub.admin.infra.persistence.entity.SysMenuEntity;
import com.agenthub.admin.infra.persistence.entity.SysRoleMenuEntity;
import com.agenthub.admin.infra.persistence.mapper.MenuMapper;
import com.agenthub.admin.infra.persistence.mapper.RoleMenuMapper;
import com.agenthub.common.web.OperatorContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 菜单仓储实现。所属域：admin。
 */
@Repository
public class MenuRepositoryImpl implements MenuRepository {

    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final OperatorContext operatorContext;

    /**
     * @param menuMapper      菜单表
     * @param roleMenuMapper  用于按角色反查菜单
     * @param operatorContext 审计人
     */
    public MenuRepositoryImpl(MenuMapper menuMapper, RoleMenuMapper roleMenuMapper, OperatorContext operatorContext) {
        this.menuMapper = menuMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.operatorContext = operatorContext;
    }

    /** {@inheritDoc} */
    @Override
    public List<Menu> listAll() {
        return menuMapper.selectList(ordered(new LambdaQueryWrapper<>())).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<Menu> listEnabled() {
        return menuMapper.selectList(ordered(new LambdaQueryWrapper<SysMenuEntity>().eq(SysMenuEntity::getStatus, 1)))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<Menu> listEnabledByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenuEntity>()
                        .in(SysRoleMenuEntity::getRoleId, roleIds))
                .stream().map(SysRoleMenuEntity::getMenuId).distinct().toList();
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return menuMapper.selectList(ordered(new LambdaQueryWrapper<SysMenuEntity>()
                        .in(SysMenuEntity::getId, menuIds)
                        .eq(SysMenuEntity::getStatus, 1)))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<String> listEnabledPermissionCodes() {
        return listEnabled().stream()
                .map(Menu::getPermission)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Menu> findById(Long id) {
        return Optional.ofNullable(toDomain(menuMapper.selectById(id)));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(Menu menu) {
        SysMenuEntity entity = new SysMenuEntity();
        copy(menu, entity);
        entity.setCreatedBy(operatorContext.userIdOrNull());
        menuMapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(Menu menu) {
        menuMapper.update(null, new LambdaUpdateWrapper<SysMenuEntity>()
                .eq(SysMenuEntity::getId, menu.getId())
                .set(SysMenuEntity::getParentId, menu.getParentId() == null ? 0L : menu.getParentId())
                .set(SysMenuEntity::getType, menu.getType())
                .set(SysMenuEntity::getName, menu.getName())
                .set(SysMenuEntity::getPath, menu.getPath())
                .set(SysMenuEntity::getComponent, menu.getComponent())
                .set(SysMenuEntity::getRedirect, menu.getRedirect())
                .set(SysMenuEntity::getPermission, menu.getPermission())
                .set(SysMenuEntity::getIcon, menu.getIcon())
                .set(SysMenuEntity::getSort, menu.getSort())
                .set(SysMenuEntity::getVisible, menu.getVisible())
                .set(SysMenuEntity::getStatus, menu.getStatus())
                .set(SysMenuEntity::getOpenType, menu.getOpenType())
                .set(SysMenuEntity::getUpdatedBy, operatorContext.userIdOrNull()));
    }

    /** {@inheritDoc} */
    @Override
    public void logicDelete(Long id, Long operatorId) {
        menuMapper.update(null, new LambdaUpdateWrapper<SysMenuEntity>()
                .eq(SysMenuEntity::getId, id)
                .set(SysMenuEntity::getUpdatedBy, operatorId));
        menuMapper.deleteById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenuEntity>().eq(SysRoleMenuEntity::getMenuId, id));
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasChildren(Long id) {
        return menuMapper.selectCount(new LambdaQueryWrapper<SysMenuEntity>().eq(SysMenuEntity::getParentId, id)) > 0;
    }

    private LambdaQueryWrapper<SysMenuEntity> ordered(LambdaQueryWrapper<SysMenuEntity> wrapper) {
        return wrapper.orderByAsc(SysMenuEntity::getSort).orderByAsc(SysMenuEntity::getId);
    }

    private void copy(Menu menu, SysMenuEntity entity) {
        entity.setParentId(menu.getParentId() == null ? 0L : menu.getParentId());
        entity.setType(menu.getType());
        entity.setName(menu.getName());
        entity.setPath(menu.getPath());
        entity.setComponent(menu.getComponent());
        entity.setRedirect(menu.getRedirect());
        entity.setPermission(blankToNull(menu.getPermission()));
        entity.setIcon(menu.getIcon());
        entity.setSort(menu.getSort() == null ? 0 : menu.getSort());
        entity.setVisible(menu.getVisible() == null ? 1 : menu.getVisible());
        entity.setStatus(menu.getStatus() == null ? 1 : menu.getStatus());
        entity.setOpenType(menu.getOpenType() == null ? 0 : menu.getOpenType());
    }

    private Menu toDomain(SysMenuEntity entity) {
        if (entity == null) {
            return null;
        }
        Menu menu = new Menu();
        menu.setId(entity.getId());
        menu.setParentId(entity.getParentId());
        menu.setType(entity.getType());
        menu.setName(entity.getName());
        menu.setPath(entity.getPath());
        menu.setComponent(entity.getComponent());
        menu.setRedirect(entity.getRedirect());
        menu.setPermission(entity.getPermission());
        menu.setIcon(entity.getIcon());
        menu.setSort(entity.getSort());
        menu.setVisible(entity.getVisible());
        menu.setStatus(entity.getStatus());
        menu.setOpenType(entity.getOpenType());
        return menu;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

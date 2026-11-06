package com.agenthub.admin.infra.persistence;

import com.agenthub.admin.domain.enums.DataScope;
import com.agenthub.admin.domain.model.Role;
import com.agenthub.admin.domain.model.RoleQuery;
import com.agenthub.admin.domain.repository.RoleRepository;
import com.agenthub.admin.infra.persistence.entity.SysRoleDeptEntity;
import com.agenthub.admin.infra.persistence.entity.SysRoleEntity;
import com.agenthub.admin.infra.persistence.entity.SysRoleMenuEntity;
import com.agenthub.admin.infra.persistence.entity.SysUserRoleEntity;
import com.agenthub.admin.infra.persistence.mapper.RoleDeptMapper;
import com.agenthub.admin.infra.persistence.mapper.RoleMapper;
import com.agenthub.admin.infra.persistence.mapper.RoleMenuMapper;
import com.agenthub.admin.infra.persistence.mapper.UserRoleMapper;
import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.web.OperatorContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 角色仓储实现。所属域：admin。删除角色时先清关联，避免留下悬空授权。
 */
@Repository
public class RoleRepositoryImpl implements RoleRepository {

    private final RoleMapper roleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final RoleDeptMapper roleDeptMapper;
    private final UserRoleMapper userRoleMapper;
    private final OperatorContext operatorContext;

    /**
     * @param roleMapper      角色表
     * @param roleMenuMapper  角色菜单
     * @param roleDeptMapper  自定义部门
     * @param userRoleMapper  用户角色
     * @param operatorContext 审计人
     */
    public RoleRepositoryImpl(RoleMapper roleMapper, RoleMenuMapper roleMenuMapper, RoleDeptMapper roleDeptMapper,
                              UserRoleMapper userRoleMapper, OperatorContext operatorContext) {
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.roleDeptMapper = roleDeptMapper;
        this.userRoleMapper = userRoleMapper;
        this.operatorContext = operatorContext;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Role> findById(Long id) {
        return Optional.ofNullable(toDomain(roleMapper.selectById(id)));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Role> findByCode(String code) {
        SysRoleEntity entity = roleMapper.selectOne(new LambdaQueryWrapper<SysRoleEntity>()
                .eq(SysRoleEntity::getCode, code)
                .last("LIMIT 1"));
        return Optional.ofNullable(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<Role> page(RoleQuery query) {
        long current = PageQuery.clampCurrent(query.getCurrent());
        long size = PageQuery.clampSize(query.getSize());
        LambdaQueryWrapper<SysRoleEntity> wrapper = new LambdaQueryWrapper<SysRoleEntity>()
                .like(query.getCode() != null && !query.getCode().isBlank(), SysRoleEntity::getCode, query.getCode())
                .like(query.getName() != null && !query.getName().isBlank(), SysRoleEntity::getName, query.getName())
                .eq(query.getStatus() != null, SysRoleEntity::getStatus, query.getStatus())
                .orderByAsc(SysRoleEntity::getSort)
                .orderByDesc(SysRoleEntity::getId);
        Page<SysRoleEntity> page = roleMapper.selectPage(new Page<>(current, size), wrapper);
        List<Role> records = page.getRecords().stream().map(this::toDomain).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /** {@inheritDoc} */
    @Override
    public List<Role> findByUserId(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRoleEntity>()
                        .eq(SysUserRoleEntity::getUserId, userId))
                .stream().map(SysUserRoleEntity::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectList(new LambdaQueryWrapper<SysRoleEntity>().in(SysRoleEntity::getId, roleIds))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(Role role) {
        SysRoleEntity entity = new SysRoleEntity();
        copy(role, entity);
        entity.setCreatedBy(operatorContext.userIdOrNull());
        roleMapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(Role role) {
        roleMapper.update(null, new LambdaUpdateWrapper<SysRoleEntity>()
                .eq(SysRoleEntity::getId, role.getId())
                .set(SysRoleEntity::getName, role.getName())
                .set(SysRoleEntity::getDataScope, role.getDataScope() == null ? DataScope.SELF.getCode() : role.getDataScope().getCode())
                .set(SysRoleEntity::getSort, role.getSort())
                .set(SysRoleEntity::getStatus, role.getStatus())
                .set(SysRoleEntity::getRemark, role.getRemark())
                .set(SysRoleEntity::getUpdatedBy, operatorContext.userIdOrNull()));
    }

    /** {@inheritDoc} */
    @Override
    public void logicDelete(Long id, Long operatorId) {
        roleMapper.update(null, new LambdaUpdateWrapper<SysRoleEntity>()
                .eq(SysRoleEntity::getId, id)
                .set(SysRoleEntity::getUpdatedBy, operatorId));
        roleMapper.deleteById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenuEntity>().eq(SysRoleMenuEntity::getRoleId, id));
        roleDeptMapper.delete(new LambdaQueryWrapper<SysRoleDeptEntity>().eq(SysRoleDeptEntity::getRoleId, id));
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRoleEntity>().eq(SysUserRoleEntity::getRoleId, id));
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> findMenuIds(Long roleId) {
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenuEntity>().eq(SysRoleMenuEntity::getRoleId, roleId))
                .stream().map(SysRoleMenuEntity::getMenuId).toList();
    }

    /** {@inheritDoc} */
    @Override
    public void replaceMenus(Long roleId, Collection<Long> menuIds) {
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenuEntity>().eq(SysRoleMenuEntity::getRoleId, roleId));
        if (menuIds == null) {
            return;
        }
        for (Long menuId : menuIds) {
            SysRoleMenuEntity link = new SysRoleMenuEntity();
            link.setRoleId(roleId);
            link.setMenuId(menuId);
            roleMenuMapper.insert(link);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> findCustomDeptIds(Long roleId) {
        return roleDeptMapper.selectList(new LambdaQueryWrapper<SysRoleDeptEntity>().eq(SysRoleDeptEntity::getRoleId, roleId))
                .stream().map(SysRoleDeptEntity::getDeptId).toList();
    }

    /** {@inheritDoc} */
    @Override
    public void replaceCustomDepts(Long roleId, Collection<Long> deptIds) {
        roleDeptMapper.delete(new LambdaQueryWrapper<SysRoleDeptEntity>().eq(SysRoleDeptEntity::getRoleId, roleId));
        if (deptIds == null) {
            return;
        }
        for (Long deptId : deptIds) {
            SysRoleDeptEntity link = new SysRoleDeptEntity();
            link.setRoleId(roleId);
            link.setDeptId(deptId);
            roleDeptMapper.insert(link);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean codeTaken(String code) {
        return roleMapper.countByCode(code) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public boolean nameTaken(String name, Long excludeId) {
        return roleMapper.countByName(name, excludeId) > 0;
    }

    private void copy(Role role, SysRoleEntity entity) {
        entity.setCode(role.getCode());
        entity.setName(role.getName());
        entity.setDataScope(role.getDataScope() == null ? DataScope.SELF.getCode() : role.getDataScope().getCode());
        entity.setSort(role.getSort() == null ? 0 : role.getSort());
        entity.setStatus(role.getStatus() == null ? 1 : role.getStatus());
        entity.setRemark(role.getRemark());
    }

    private Role toDomain(SysRoleEntity entity) {
        if (entity == null) {
            return null;
        }
        Role role = new Role();
        role.setId(entity.getId());
        role.setCode(entity.getCode());
        role.setName(entity.getName());
        role.setDataScope(DataScope.of(entity.getDataScope()));
        role.setSort(entity.getSort());
        role.setStatus(entity.getStatus());
        role.setRemark(entity.getRemark());
        // 域模型仍用 createTime；库列已是 create_time
        role.setCreateTime(entity.getCreateTime());
        return role;
    }
}

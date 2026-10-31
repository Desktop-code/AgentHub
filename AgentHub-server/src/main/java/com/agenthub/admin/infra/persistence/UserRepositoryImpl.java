package com.agenthub.admin.infra.persistence;

import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.domain.model.User;
import com.agenthub.admin.domain.model.UserQuery;
import com.agenthub.admin.domain.repository.UserRepository;
import com.agenthub.admin.infra.persistence.entity.SysUserEntity;
import com.agenthub.admin.infra.persistence.entity.SysUserRoleEntity;
import com.agenthub.admin.infra.persistence.entity.UserRoleRow;
import com.agenthub.admin.infra.persistence.mapper.UserMapper;
import com.agenthub.admin.infra.persistence.mapper.UserRoleMapper;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.web.OperatorContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户仓储实现。所属域：admin。
 * 约束：分页 SQL 不查出 password_hash；资料更新用列清单，避免把哈希写丢或写脏。
 */
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final OperatorContext operatorContext;

    /**
     * @param userMapper      用户表
     * @param userRoleMapper  用户角色关联
     * @param operatorContext 写入审计人，未登录时允许为空
     */
    public UserRepositoryImpl(UserMapper userMapper, UserRoleMapper userRoleMapper, OperatorContext operatorContext) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.operatorContext = operatorContext;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<User> findByUsername(String username) {
        SysUserEntity entity = userMapper.selectOne(new LambdaQueryWrapper<SysUserEntity>()
                .eq(SysUserEntity::getUsername, username)
                .last("LIMIT 1"));
        return Optional.ofNullable(toDomain(entity, true));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<User> findById(Long id) {
        User user = toDomain(userMapper.selectById(id), true);
        if (user != null) {
            fillRoles(List.of(user));
        }
        return Optional.ofNullable(user);
    }

    /** {@inheritDoc} */
    @Override
    public boolean usernameTaken(String username) {
        return userMapper.countByUsername(username) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<User> page(UserQuery query, DataScopeSpec scope) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        IPage<SysUserEntity> page = userMapper.selectPageByScope(
                new Page<>(current, size), query.getUsername(), query.getStatus(), query.getDeptId(), scope);
        List<User> records = new ArrayList<>();
        for (SysUserEntity entity : page.getRecords()) {
            records.add(toDomain(entity, false));
        }
        fillRoles(records);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(User user) {
        SysUserEntity entity = new SysUserEntity();
        entity.setDeptId(user.getDeptId());
        entity.setUsername(user.getUsername());
        entity.setPasswordHash(user.getPasswordHash());
        entity.setNickname(user.getNickname());
        entity.setEmail(user.getEmail());
        entity.setPhone(user.getPhone());
        entity.setStatus(user.getStatus() == null ? 1 : user.getStatus());
        entity.setIsAdmin(user.isPlatformAdmin() ? 1 : 0);
        entity.setGender(0);
        entity.setCreatedBy(operatorContext.userIdOrNull());
        userMapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateProfile(User user) {
        userMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, user.getId())
                .set(SysUserEntity::getDeptId, user.getDeptId())
                .set(SysUserEntity::getNickname, user.getNickname())
                .set(SysUserEntity::getEmail, user.getEmail())
                .set(SysUserEntity::getPhone, user.getPhone())
                .set(SysUserEntity::getStatus, user.getStatus())
                .set(SysUserEntity::getUpdatedBy, operatorContext.userIdOrNull()));
    }

    /** {@inheritDoc} */
    @Override
    public void updatePassword(Long id, String passwordHash, Long operatorId) {
        userMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, id)
                .set(SysUserEntity::getPasswordHash, passwordHash)
                .set(SysUserEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void updateLoginAudit(Long id, String ip) {
        userMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, id)
                .set(SysUserEntity::getLastLoginTime, LocalDateTime.now())
                .set(SysUserEntity::getLastLoginIp, ip));
    }

    /** {@inheritDoc} */
    @Override
    public void logicDelete(Long id, Long operatorId) {
        userMapper.update(null, new LambdaUpdateWrapper<SysUserEntity>()
                .eq(SysUserEntity::getId, id)
                .set(SysUserEntity::getUpdatedBy, operatorId));
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRoleEntity>().eq(SysUserRoleEntity::getUserId, id));
    }

    /** {@inheritDoc} */
    @Override
    public void replaceRoles(Long userId, Collection<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRoleEntity>().eq(SysUserRoleEntity::getUserId, userId));
        if (roleIds == null) {
            return;
        }
        for (Long roleId : roleIds) {
            SysUserRoleEntity link = new SysUserRoleEntity();
            link.setUserId(userId);
            link.setRoleId(roleId);
            userRoleMapper.insert(link);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean activeAdmin(Long userId) {
        return userMapper.countActiveAdmin(userId) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public long countOtherActiveAdmins(Long userId) {
        return userMapper.countOtherActiveAdmins(userId);
    }

    /**
     * @param keepPassword 登录比对需要哈希；列表查询的投影里没有该列，传 false
     */
    private User toDomain(SysUserEntity entity, boolean keepPassword) {
        if (entity == null) {
            return null;
        }
        User user = new User();
        user.setId(entity.getId());
        user.setDeptId(entity.getDeptId());
        user.setDeptName(entity.getDeptName());
        user.setDeptPath(entity.getDeptPath());
        user.setUsername(entity.getUsername());
        user.setNickname(entity.getNickname());
        user.setEmail(entity.getEmail());
        user.setPhone(entity.getPhone());
        user.setStatus(entity.getStatus());
        user.setPlatformAdmin(entity.getIsAdmin() != null && entity.getIsAdmin() == 1);
        // 域模型仍用 createTime / lastLoginTime；库列已是 create_time / last_login_time
        user.setLastLoginTime(entity.getLastLoginTime());
        user.setCreateTime(entity.getCreateTime());
        if (keepPassword) {
            user.setPasswordHash(entity.getPasswordHash());
        }
        return user;
    }

    private void fillRoles(List<User> users) {
        if (users.isEmpty()) {
            return;
        }
        List<Long> ids = users.stream().map(User::getId).toList();
        Map<Long, User> byId = new HashMap<>();
        for (User user : users) {
            byId.put(user.getId(), user);
        }
        for (UserRoleRow row : userMapper.selectRolesByUserIds(ids)) {
            User user = byId.get(row.getUserId());
            if (user == null) {
                continue;
            }
            user.getRoleIds().add(row.getRoleId());
            if (row.getRoleName() != null) {
                user.getRoleNames().add(row.getRoleName());
            }
        }
    }
}

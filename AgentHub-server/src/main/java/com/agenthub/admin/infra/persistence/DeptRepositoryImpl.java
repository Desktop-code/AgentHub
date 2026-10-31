package com.agenthub.admin.infra.persistence;

import com.agenthub.admin.domain.model.Dept;
import com.agenthub.admin.domain.repository.DeptRepository;
import com.agenthub.admin.infra.persistence.entity.SysDeptEntity;
import com.agenthub.admin.infra.persistence.entity.SysUserEntity;
import com.agenthub.admin.infra.persistence.mapper.DeptMapper;
import com.agenthub.admin.infra.persistence.mapper.UserMapper;
import com.agenthub.common.web.OperatorContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 部门仓储实现。所属域：admin。
 * 约束：path 为 0,1,3 逗号层级串，移动时只按前缀改写后代；
 * 查询过滤用「等于前缀 / 前缀+逗号开头」，不使用 FIND_IN_SET。
 */
@Repository
public class DeptRepositoryImpl implements DeptRepository {

    private final DeptMapper deptMapper;
    private final UserMapper userMapper;
    private final OperatorContext operatorContext;

    /**
     * @param deptMapper      部门表
     * @param userMapper      用来判断部门下是否还有人
     * @param operatorContext 审计人
     */
    public DeptRepositoryImpl(DeptMapper deptMapper, UserMapper userMapper, OperatorContext operatorContext) {
        this.deptMapper = deptMapper;
        this.userMapper = userMapper;
        this.operatorContext = operatorContext;
    }

    /** {@inheritDoc} */
    @Override
    public List<Dept> listAll() {
        return deptMapper.selectList(new LambdaQueryWrapper<SysDeptEntity>()
                        .orderByAsc(SysDeptEntity::getSort)
                        .orderByAsc(SysDeptEntity::getId))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Dept> findById(Long id) {
        return Optional.ofNullable(toDomain(deptMapper.selectById(id)));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(Dept dept) {
        SysDeptEntity entity = new SysDeptEntity();
        entity.setParentId(dept.getParentId() == null ? 0L : dept.getParentId());
        entity.setName(dept.getName());
        entity.setPath(dept.getPath() == null ? "" : dept.getPath());
        entity.setSort(dept.getSort() == null ? 0 : dept.getSort());
        entity.setLeaderUserId(dept.getLeaderUserId());
        entity.setPhone(dept.getPhone());
        entity.setStatus(dept.getStatus() == null ? 1 : dept.getStatus());
        entity.setCreatedBy(operatorContext.userIdOrNull());
        deptMapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateProfile(Dept dept) {
        deptMapper.update(null, new LambdaUpdateWrapper<SysDeptEntity>()
                .eq(SysDeptEntity::getId, dept.getId())
                .set(SysDeptEntity::getName, dept.getName())
                .set(SysDeptEntity::getSort, dept.getSort())
                .set(SysDeptEntity::getLeaderUserId, dept.getLeaderUserId())
                .set(SysDeptEntity::getPhone, dept.getPhone())
                .set(SysDeptEntity::getStatus, dept.getStatus())
                .set(SysDeptEntity::getUpdatedBy, operatorContext.userIdOrNull()));
    }

    /** {@inheritDoc} */
    @Override
    public void updatePath(Long id, String path) {
        deptMapper.update(null, new LambdaUpdateWrapper<SysDeptEntity>()
                .eq(SysDeptEntity::getId, id)
                .set(SysDeptEntity::getPath, path)
                .set(SysDeptEntity::getUpdatedBy, operatorContext.userIdOrNull()));
    }

    /** {@inheritDoc} */
    @Override
    public void rewritePath(Long id, Long parentId, String oldPrefix, String newPrefix, Long operatorId) {
        // 旧 path 为空时不能用 LIKE '%'，否则会改写整棵树
        if (oldPrefix == null || oldPrefix.isBlank()) {
            deptMapper.update(null, new LambdaUpdateWrapper<SysDeptEntity>()
                    .eq(SysDeptEntity::getId, id)
                    .set(SysDeptEntity::getParentId, parentId)
                    .set(SysDeptEntity::getPath, newPrefix)
                    .set(SysDeptEntity::getUpdatedBy, operatorId));
            return;
        }
        deptMapper.rewritePath(id, parentId, oldPrefix, newPrefix, operatorId);
    }

    /** {@inheritDoc} */
    @Override
    public void logicDelete(Long id, Long operatorId) {
        deptMapper.update(null, new LambdaUpdateWrapper<SysDeptEntity>()
                .eq(SysDeptEntity::getId, id)
                .set(SysDeptEntity::getUpdatedBy, operatorId));
        deptMapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasChildren(Long id) {
        return deptMapper.selectCount(new LambdaQueryWrapper<SysDeptEntity>().eq(SysDeptEntity::getParentId, id)) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasUsers(Long id) {
        return userMapper.selectCount(new LambdaQueryWrapper<SysUserEntity>().eq(SysUserEntity::getDeptId, id)) > 0;
    }

    private Dept toDomain(SysDeptEntity entity) {
        if (entity == null) {
            return null;
        }
        Dept dept = new Dept();
        dept.setId(entity.getId());
        dept.setParentId(entity.getParentId());
        dept.setName(entity.getName());
        dept.setPath(entity.getPath());
        dept.setSort(entity.getSort());
        dept.setLeaderUserId(entity.getLeaderUserId());
        dept.setPhone(entity.getPhone());
        dept.setStatus(entity.getStatus());
        return dept;
    }
}

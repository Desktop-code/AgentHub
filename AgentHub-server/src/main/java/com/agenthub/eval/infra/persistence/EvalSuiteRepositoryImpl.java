package com.agenthub.eval.infra.persistence;

import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.text.LikeText;
import com.agenthub.eval.domain.model.EvalSuite;
import com.agenthub.eval.domain.model.EvalSuiteQuery;
import com.agenthub.eval.domain.repository.EvalSuiteRepository;
import com.agenthub.eval.infra.persistence.entity.EvalSuiteEntity;
import com.agenthub.eval.infra.persistence.mapper.EvalSuiteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 评测套件仓储实现。所属域：eval 的 infra。Entity 不出本包。
 * <p>租户过滤：列表强制带 tenant_id；按 id 查询故意不带（供应用层区分 404 与越权），
 * 写操作额外带 tenant_id 条件，避免应用层判断漏掉时被越权改。
 */
@Repository
public class EvalSuiteRepositoryImpl implements EvalSuiteRepository {

    private final EvalSuiteMapper mapper;

    /**
     * @param mapper eval_suite
     */
    public EvalSuiteRepositoryImpl(EvalSuiteMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<EvalSuite> page(EvalSuiteQuery query) {
        long current = PageQuery.clampCurrent(query.getCurrent());
        long size = PageQuery.clampSize(query.getSize());
        LambdaQueryWrapper<EvalSuiteEntity> wrapper = new LambdaQueryWrapper<>();
        // 只读样例与本租户数据并集；两者都在同一条 WHERE 里，避免两次查询合并后再排序
        if (query.isIncludePlatform()) {
            wrapper.and(scope -> scope.eq(EvalSuiteEntity::getTenantId, query.getTenantId())
                    .or()
                    .eq(EvalSuiteEntity::getTenantId, EvalSuite.PLATFORM_TENANT_ID));
        } else {
            wrapper.eq(EvalSuiteEntity::getTenantId, query.getTenantId());
        }
        String keyword = LikeText.contains(query.getKeyword());
        if (keyword != null) {
            wrapper.and(one -> one.like(EvalSuiteEntity::getSuiteKey, keyword)
                    .or()
                    .like(EvalSuiteEntity::getName, keyword));
        }
        wrapper.orderByDesc(EvalSuiteEntity::getId);
        IPage<EvalSuiteEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalSuite> findById(Long id) {
        EvalSuiteEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalSuite> findByTenantAndKey(long tenantId, String suiteKey) {
        EvalSuiteEntity entity = mapper.selectOne(new LambdaQueryWrapper<EvalSuiteEntity>()
                .eq(EvalSuiteEntity::getTenantId, tenantId)
                .eq(EvalSuiteEntity::getSuiteKey, suiteKey));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public long countByTenantAndKeyIncludingDeleted(long tenantId, String suiteKey) {
        return mapper.countByKeyIncludingDeleted(tenantId, suiteKey);
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(EvalSuite suite, Long operatorId) {
        EvalSuiteEntity entity = new EvalSuiteEntity();
        entity.setTenantId(suite.getTenantId());
        entity.setSuiteKey(suite.getSuiteKey());
        entity.setName(suite.getName());
        entity.setDescription(suite.getDescription());
        entity.setCurrentVersion(suite.getCurrentVersion());
        entity.setDefaultConfigJson(suite.getDefaultConfigJson());
        entity.setEnabled(suite.getEnabled());
        entity.setCreateBy(operatorId);
        entity.setUpdateBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateMeta(EvalSuite suite, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<EvalSuiteEntity>()
                .eq(EvalSuiteEntity::getId, suite.getId())
                .eq(EvalSuiteEntity::getTenantId, suite.getTenantId())
                .set(EvalSuiteEntity::getName, suite.getName())
                .set(EvalSuiteEntity::getDescription, suite.getDescription())
                .set(EvalSuiteEntity::getDefaultConfigJson, suite.getDefaultConfigJson())
                .set(EvalSuiteEntity::getEnabled, suite.getEnabled())
                .set(EvalSuiteEntity::getUpdateBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public int bumpVersion(Long id, long tenantId, int expectedVersion, int newVersion, Long operatorId) {
        return mapper.update(null, new LambdaUpdateWrapper<EvalSuiteEntity>()
                .eq(EvalSuiteEntity::getId, id)
                .eq(EvalSuiteEntity::getTenantId, tenantId)
                // CAS 条件：库里版本必须仍是调用方读到的那个，防止并发导入算出同一个新版本号
                .eq(EvalSuiteEntity::getCurrentVersion, expectedVersion)
                .set(EvalSuiteEntity::getCurrentVersion, newVersion)
                .set(EvalSuiteEntity::getUpdateBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public int logicalDelete(Long id, long tenantId, Long operatorId) {
        // 先记更新人；逻辑删除本身只会写 deleted，不带 update_by
        int touched = mapper.update(null, new LambdaUpdateWrapper<EvalSuiteEntity>()
                .eq(EvalSuiteEntity::getId, id)
                .eq(EvalSuiteEntity::getTenantId, tenantId)
                .set(EvalSuiteEntity::getUpdateBy, operatorId));
        if (touched > 0) {
            mapper.deleteById(id);
        }
        return touched;
    }


    private EvalSuite toDomain(EvalSuiteEntity entity) {
        EvalSuite suite = new EvalSuite();
        suite.setId(entity.getId());
        suite.setTenantId(entity.getTenantId());
        suite.setSuiteKey(entity.getSuiteKey());
        suite.setName(entity.getName());
        suite.setDescription(entity.getDescription());
        suite.setCurrentVersion(entity.getCurrentVersion());
        suite.setDefaultConfigJson(entity.getDefaultConfigJson());
        suite.setEnabled(entity.getEnabled());
        suite.setCreatedBy(entity.getCreateBy());
        suite.setCreateTime(entity.getCreateTime());
        suite.setUpdatedBy(entity.getUpdateBy());
        suite.setUpdateTime(entity.getUpdateTime());
        return suite;
    }
}

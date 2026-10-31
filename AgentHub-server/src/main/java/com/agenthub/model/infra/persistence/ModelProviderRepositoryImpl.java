package com.agenthub.model.infra.persistence;

import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.model.infra.persistence.entity.ModelProviderEntity;
import com.agenthub.model.infra.persistence.mapper.ModelProviderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 供应商仓储实现。所属域：model。Entity 不出本包。
 */
@Repository
public class ModelProviderRepositoryImpl implements ModelProviderRepository {

    private final ModelProviderMapper mapper;

    /**
     * @param mapper 供应商表
     */
    public ModelProviderRepositoryImpl(ModelProviderMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<ModelProvider> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<ModelProviderEntity>()
                        .orderByDesc(ModelProviderEntity::getId))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ModelProvider> findById(Long id) {
        ModelProviderEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean nameExists(String name, Long excludeId) {
        LambdaQueryWrapper<ModelProviderEntity> wrapper = new LambdaQueryWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getName, name);
        if (excludeId != null) {
            wrapper.ne(ModelProviderEntity::getId, excludeId);
        }
        Long total = mapper.selectCount(wrapper);
        return total != null && total > 0;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(ModelProvider provider, Long operatorId) {
        ModelProviderEntity entity = new ModelProviderEntity();
        entity.setName(provider.getName());
        entity.setProtocol(provider.getProtocol());
        entity.setBaseUrl(provider.getBaseUrl());
        entity.setStatus(provider.getStatus());
        entity.setRemark(provider.getRemark());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(ModelProvider provider, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getId, provider.getId())
                .set(ModelProviderEntity::getName, provider.getName())
                .set(ModelProviderEntity::getProtocol, provider.getProtocol())
                .set(ModelProviderEntity::getBaseUrl, provider.getBaseUrl())
                .set(ModelProviderEntity::getStatus, provider.getStatus())
                .set(ModelProviderEntity::getRemark, provider.getRemark())
                .set(ModelProviderEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        touchOperator(id, operatorId);
        mapper.deleteById(id);
    }

    private void touchOperator(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ModelProviderEntity>()
                .eq(ModelProviderEntity::getId, id)
                .set(ModelProviderEntity::getUpdatedBy, operatorId));
    }

    private ModelProvider toDomain(ModelProviderEntity entity) {
        ModelProvider provider = new ModelProvider();
        provider.setId(entity.getId());
        provider.setName(entity.getName());
        provider.setProtocol(entity.getProtocol());
        provider.setBaseUrl(entity.getBaseUrl());
        provider.setStatus(entity.getStatus());
        provider.setRemark(entity.getRemark());
        provider.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        provider.setCreateTime(entity.getCreateTime());
        provider.setUpdatedBy(entity.getUpdatedBy());
        provider.setUpdateTime(entity.getUpdateTime());
        return provider;
    }
}

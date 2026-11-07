package com.agenthub.model.infra.persistence;

import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.text.LikeText;
import com.agenthub.model.domain.model.InstanceQuery;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.infra.persistence.entity.ModelInstanceEntity;
import com.agenthub.model.infra.persistence.mapper.ModelInstanceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 实例仓储实现。所属域：model。关键词里的 % 和 _ 不当作通配符。
 */
@Repository
public class ModelInstanceRepositoryImpl implements ModelInstanceRepository {

    private final ModelInstanceMapper mapper;

    /**
     * @param mapper 实例表
     */
    public ModelInstanceRepositoryImpl(ModelInstanceMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<ModelInstance> page(InstanceQuery query) {
        long current = PageQuery.clampCurrent(query.getCurrent());
        long size = PageQuery.clampSize(query.getSize());
        LambdaQueryWrapper<ModelInstanceEntity> wrapper = new LambdaQueryWrapper<ModelInstanceEntity>()
                .orderByDesc(ModelInstanceEntity::getId);
        if (query.getProviderId() != null) {
            wrapper.eq(ModelInstanceEntity::getProviderId, query.getProviderId());
        }
        // 优先 IN（规范码+旧码）；否则单值精确匹配
        if (query.getCapabilities() != null && !query.getCapabilities().isEmpty()) {
            wrapper.in(ModelInstanceEntity::getCapability, query.getCapabilities());
        } else if (query.getCapability() != null && !query.getCapability().isBlank()) {
            wrapper.eq(ModelInstanceEntity::getCapability, query.getCapability());
        }
        String keyword = LikeText.contains(query.getKeyword());
        if (keyword != null) {
            wrapper.and(one -> one.like(ModelInstanceEntity::getDisplayName, keyword)
                    .or()
                    .like(ModelInstanceEntity::getModelId, keyword));
        }
        IPage<ModelInstanceEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ModelInstance> findById(Long id) {
        ModelInstanceEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ModelInstance> findByIdIncludingDeleted(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        // 走自定义 SQL，绕过 TableLogic，才能拼到已删实例的历史用量
        ModelInstanceEntity entity = mapper.selectByIdIncludingDeleted(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public List<ModelInstance> listEnabledByCapability(String capability) {
        if (capability == null || capability.isBlank()) {
            return List.of();
        }
        // 平台默认按 id 升序，解析器取第一条已启用实例
        LambdaQueryWrapper<ModelInstanceEntity> wrapper = new LambdaQueryWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getCapability, capability.trim())
                .eq(ModelInstanceEntity::getStatus, 1)
                .orderByAsc(ModelInstanceEntity::getId);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ModelInstance> findPlatformLocked(ModelCapability capability) {
        if (capability == null) {
            return Optional.empty();
        }
        // 同时匹配规范码与库内遗留码（EMBED → EMBEDDING），避免洗库前进度表解析不到
        LambdaQueryWrapper<ModelInstanceEntity> wrapper = new LambdaQueryWrapper<ModelInstanceEntity>()
                .in(ModelInstanceEntity::getCapability, ModelCapability.storageCodes(capability))
                .eq(ModelInstanceEntity::getPlatformLocked, 1)
                .orderByAsc(ModelInstanceEntity::getId);
        return mapper.selectList(wrapper).stream().findFirst().map(this::toDomain);
    }

    /** {@inheritDoc} */
    @Override
    public int countPlatformLockedByProvider(Long providerId) {
        // 平台级实例受全局唯一索引保护，这里只数未删除行；逻辑删行不挡厂商卡片删除
        Long count = mapper.selectCount(new LambdaQueryWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getProviderId, providerId)
                .eq(ModelInstanceEntity::getPlatformLocked, 1));
        return count == null ? 0 : count.intValue();
    }

    /** {@inheritDoc} */
    @Override
    public Set<Long> platformLockedProviderIds() {
        // 一次查全部平台级行（全局最多 2 条），只取供应商 id，避免按卡片逐条计数
        List<ModelInstanceEntity> rows = mapper.selectList(new LambdaQueryWrapper<ModelInstanceEntity>()
                .select(ModelInstanceEntity::getProviderId)
                .eq(ModelInstanceEntity::getPlatformLocked, 1));
        Set<Long> providerIds = new LinkedHashSet<>();
        for (ModelInstanceEntity row : rows) {
            if (row != null && row.getProviderId() != null) {
                providerIds.add(row.getProviderId());
            }
        }
        return providerIds;
    }

    /** {@inheritDoc} */
    @Override
    public int countByProviderId(Long providerId) {
        return count(new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getProviderId, providerId));
    }

    /** {@inheritDoc} */
    @Override
    public int countByCredentialId(Long credentialId) {
        return count(new LambdaQueryWrapper<ModelInstanceEntity>().eq(ModelInstanceEntity::getCredentialId, credentialId));
    }

    /** {@inheritDoc} */
    @Override
    public boolean displayNameExists(Long providerId, String displayName, Long excludeId) {
        // 只比同供应商：跨供应商同名（如都叫 qwen-tts）是合法场景，不能当成重复
        LambdaQueryWrapper<ModelInstanceEntity> wrapper = new LambdaQueryWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getProviderId, providerId)
                .eq(ModelInstanceEntity::getDisplayName, displayName);
        if (excludeId != null) {
            wrapper.ne(ModelInstanceEntity::getId, excludeId);
        }
        return count(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public boolean modelIdCapabilityExists(Long providerId, String modelId, String capability, Long excludeId) {
        // 只按 provider_id 判定：同供应商下多凭证也算同模型，避免重复实例
        LambdaQueryWrapper<ModelInstanceEntity> wrapper = new LambdaQueryWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getProviderId, providerId)
                .eq(ModelInstanceEntity::getModelId, modelId)
                .eq(ModelInstanceEntity::getCapability, capability);
        if (excludeId != null) {
            wrapper.ne(ModelInstanceEntity::getId, excludeId);
        }
        return count(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(ModelInstance instance, Long operatorId) {
        ModelInstanceEntity entity = new ModelInstanceEntity();
        entity.setProviderId(instance.getProviderId());
        entity.setCredentialId(instance.getCredentialId());
        entity.setDisplayName(instance.getDisplayName());
        entity.setModelId(instance.getModelId());
        entity.setCapability(instance.getCapability());
        entity.setStatus(instance.getStatus());
        entity.setExtraJson(instance.getExtraJson());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(ModelInstance instance, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getId, instance.getId())
                .set(ModelInstanceEntity::getProviderId, instance.getProviderId())
                .set(ModelInstanceEntity::getCredentialId, instance.getCredentialId())
                .set(ModelInstanceEntity::getDisplayName, instance.getDisplayName())
                .set(ModelInstanceEntity::getModelId, instance.getModelId())
                .set(ModelInstanceEntity::getCapability, instance.getCapability())
                .set(ModelInstanceEntity::getStatus, instance.getStatus())
                .set(ModelInstanceEntity::getExtraJson, instance.getExtraJson())
                .set(ModelInstanceEntity::getUpdatedBy, operatorId));
    }

    /**
     * {@inheritDoc}
     * 只更新三列，避免一次探活把展示名或凭证绑回写掉。
     */
    @Override
    public void recordProbe(Long id, LocalDateTime testedTime, int ok, String message) {
        mapper.update(null, new LambdaUpdateWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getId, id)
                .set(ModelInstanceEntity::getLastTestTime, testedTime)
                .set(ModelInstanceEntity::getLastTestOk, ok)
                .set(ModelInstanceEntity::getLastTestMsg, message));
    }

    /** {@inheritDoc} */
    @Override
    public void recordEmbeddingDim(Long id, Integer dimension) {
        // 维度快照只由连通测试写；单独一条 UPDATE，失败/未解析时清空，避免留下过期维
        mapper.update(null, new LambdaUpdateWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getId, id)
                .set(ModelInstanceEntity::getEmbeddingDim, dimension));
    }

    /** {@inheritDoc} */
    @Override
    public void updatePlatformLock(Long id, Integer platformLocked, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getId, id)
                .set(ModelInstanceEntity::getPlatformLocked, platformLocked)
                .set(ModelInstanceEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ModelInstanceEntity>()
                .eq(ModelInstanceEntity::getId, id)
                .set(ModelInstanceEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    private int count(LambdaQueryWrapper<ModelInstanceEntity> wrapper) {
        Long total = mapper.selectCount(wrapper);
        return total == null ? 0 : total.intValue();
    }


    private ModelInstance toDomain(ModelInstanceEntity entity) {
        ModelInstance instance = new ModelInstance();
        instance.setId(entity.getId());
        instance.setProviderId(entity.getProviderId());
        instance.setCredentialId(entity.getCredentialId());
        instance.setDisplayName(entity.getDisplayName());
        instance.setModelId(entity.getModelId());
        instance.setCapability(entity.getCapability());
        instance.setStatus(entity.getStatus());
        instance.setPlatformLocked(entity.getPlatformLocked());
        instance.setExtraJson(entity.getExtraJson());
        instance.setDeleted(entity.getDeleted());
        // 域/API 仍用 lastTestTime / createTime；库列已是 last_test_time / create_time
        instance.setLastTestTime(entity.getLastTestTime());
        instance.setLastTestOk(entity.getLastTestOk());
        instance.setLastTestMsg(entity.getLastTestMsg());
        instance.setEmbeddingDim(entity.getEmbeddingDim());
        instance.setCreatedBy(entity.getCreatedBy());
        instance.setCreateTime(entity.getCreateTime());
        instance.setUpdatedBy(entity.getUpdatedBy());
        instance.setUpdateTime(entity.getUpdateTime());
        return instance;
    }
}

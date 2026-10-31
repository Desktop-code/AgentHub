package com.agenthub.model.infra.persistence;

import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.infra.persistence.entity.ModelCredentialEntity;
import com.agenthub.model.infra.persistence.mapper.ModelCredentialMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 凭证仓储实现。所属域：model。密文只在实体和领域对象之间搬运，不写日志。
 */
@Repository
public class ModelCredentialRepositoryImpl implements ModelCredentialRepository {

    private final ModelCredentialMapper mapper;

    /**
     * @param mapper 凭证表
     */
    public ModelCredentialRepositoryImpl(ModelCredentialMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<ModelCredential> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<ModelCredentialEntity>()
                        .orderByAsc(ModelCredentialEntity::getId))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<ModelCredential> listByProviderId(Long providerId) {
        return mapper.selectList(new LambdaQueryWrapper<ModelCredentialEntity>()
                        .eq(ModelCredentialEntity::getProviderId, providerId)
                        .orderByAsc(ModelCredentialEntity::getId))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ModelCredential> findById(Long id) {
        ModelCredentialEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean nameExists(Long providerId, String name, Long excludeId) {
        LambdaQueryWrapper<ModelCredentialEntity> wrapper = new LambdaQueryWrapper<ModelCredentialEntity>()
                .eq(ModelCredentialEntity::getProviderId, providerId)
                .eq(ModelCredentialEntity::getName, name);
        if (excludeId != null) {
            wrapper.ne(ModelCredentialEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(ModelCredential credential, Long operatorId) {
        ModelCredentialEntity entity = new ModelCredentialEntity();
        entity.setProviderId(credential.getProviderId());
        entity.setName(credential.getName());
        entity.setApiKeyCipher(credential.getApiKeyCipher());
        entity.setApiKeyMask(credential.getApiKeyMask());
        entity.setStatus(credential.getStatus());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(ModelCredential credential, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ModelCredentialEntity>()
                .eq(ModelCredentialEntity::getId, credential.getId())
                .set(ModelCredentialEntity::getName, credential.getName())
                .set(ModelCredentialEntity::getApiKeyCipher, credential.getApiKeyCipher())
                .set(ModelCredentialEntity::getApiKeyMask, credential.getApiKeyMask())
                .set(ModelCredentialEntity::getStatus, credential.getStatus())
                .set(ModelCredentialEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void rewriteCipher(Long id, String cipher, String mask) {
        // 只动密文与掩码：迁移属系统行为，不覆盖 updated_by，也不碰名称与状态
        mapper.update(null, new LambdaUpdateWrapper<ModelCredentialEntity>()
                .eq(ModelCredentialEntity::getId, id)
                .set(ModelCredentialEntity::getApiKeyCipher, cipher)
                .set(ModelCredentialEntity::getApiKeyMask, mask));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ModelCredentialEntity>()
                .eq(ModelCredentialEntity::getId, id)
                .set(ModelCredentialEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    private ModelCredential toDomain(ModelCredentialEntity entity) {
        ModelCredential credential = new ModelCredential();
        credential.setId(entity.getId());
        credential.setProviderId(entity.getProviderId());
        credential.setName(entity.getName());
        credential.setApiKeyCipher(entity.getApiKeyCipher());
        credential.setApiKeyMask(entity.getApiKeyMask());
        credential.setStatus(entity.getStatus());
        credential.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        credential.setCreateTime(entity.getCreateTime());
        credential.setUpdatedBy(entity.getUpdatedBy());
        credential.setUpdateTime(entity.getUpdateTime());
        return credential;
    }
}

package com.agenthub.tool.infra.persistence;

import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.infra.persistence.entity.ToolSourceEntity;
import com.agenthub.tool.infra.persistence.mapper.ToolSourceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 工具源仓储实现。所属域：tool。Entity 不出本包。
 */
@Repository
public class ToolSourceRepositoryImpl implements ToolSourceRepository {

    private final ToolSourceMapper mapper;

    /**
     * @param mapper 工具源表
     */
    public ToolSourceRepositoryImpl(ToolSourceMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<ToolSource> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<ToolSourceEntity>()
                        .orderByDesc(ToolSourceEntity::getId))
                .stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> listIdsByType(String type) {
        return mapper.selectList(new LambdaQueryWrapper<ToolSourceEntity>()
                        .select(ToolSourceEntity::getId)
                        .eq(ToolSourceEntity::getType, type))
                .stream().map(ToolSourceEntity::getId).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ToolSource> findById(Long id) {
        ToolSourceEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public boolean nameExists(String name, Long excludeId) {
        LambdaQueryWrapper<ToolSourceEntity> wrapper = new LambdaQueryWrapper<ToolSourceEntity>()
                .eq(ToolSourceEntity::getName, name);
        if (excludeId != null) {
            wrapper.ne(ToolSourceEntity::getId, excludeId);
        }
        Long total = mapper.selectCount(wrapper);
        return total != null && total > 0;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(ToolSource source, Long operatorId) {
        ToolSourceEntity entity = new ToolSourceEntity();
        entity.setName(source.getName());
        entity.setType(source.getType());
        entity.setTransport(source.getTransport());
        entity.setBaseUrl(source.getBaseUrl());
        entity.setAuthHeaderCipher(source.getAuthHeaderCipher());
        entity.setAuthHeaderMask(source.getAuthHeaderMask());
        entity.setStatus(source.getStatus());
        entity.setRemark(source.getRemark());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /**
     * {@inheritDoc}
     * 不 set 密文列，避免编辑资料时把鉴权头写成 null。
     */
    @Override
    public void updateProfile(ToolSource source, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ToolSourceEntity>()
                .eq(ToolSourceEntity::getId, source.getId())
                .set(ToolSourceEntity::getName, source.getName())
                .set(ToolSourceEntity::getTransport, source.getTransport())
                .set(ToolSourceEntity::getBaseUrl, source.getBaseUrl())
                .set(ToolSourceEntity::getStatus, source.getStatus())
                .set(ToolSourceEntity::getRemark, source.getRemark())
                .set(ToolSourceEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void updateAuth(Long id, String cipher, String mask, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ToolSourceEntity>()
                .eq(ToolSourceEntity::getId, id)
                .set(ToolSourceEntity::getAuthHeaderCipher, cipher)
                .set(ToolSourceEntity::getAuthHeaderMask, mask)
                .set(ToolSourceEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void rewriteCipher(Long id, String cipher, String mask) {
        // 只动密文与掩码，不写 updated_by：迁移属系统行为，不应把操作人清空或伪装成人工修改
        mapper.update(null, new LambdaUpdateWrapper<ToolSourceEntity>()
                .eq(ToolSourceEntity::getId, id)
                .set(ToolSourceEntity::getAuthHeaderCipher, cipher)
                .set(ToolSourceEntity::getAuthHeaderMask, mask));
    }

    /** {@inheritDoc} */
    @Override
    public void recordSync(Long id, LocalDateTime syncedTime, int ok, String message, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ToolSourceEntity>()
                .eq(ToolSourceEntity::getId, id)
                .set(ToolSourceEntity::getLastSyncTime, syncedTime)
                .set(ToolSourceEntity::getLastSyncOk, ok)
                .set(ToolSourceEntity::getLastSyncMsg, message)
                .set(ToolSourceEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<ToolSourceEntity>()
                .eq(ToolSourceEntity::getId, id)
                .set(ToolSourceEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    private ToolSource toDomain(ToolSourceEntity entity) {
        ToolSource source = new ToolSource();
        source.setId(entity.getId());
        source.setName(entity.getName());
        source.setType(entity.getType());
        source.setTransport(entity.getTransport());
        source.setBaseUrl(entity.getBaseUrl());
        source.setAuthHeaderCipher(entity.getAuthHeaderCipher());
        source.setAuthHeaderMask(entity.getAuthHeaderMask());
        source.setStatus(entity.getStatus());
        source.setRemark(entity.getRemark());
        // 域/API 仍用 lastSyncTime/createTime；库列已是 last_sync_time/create_time
        source.setLastSyncTime(entity.getLastSyncTime());
        source.setLastSyncOk(entity.getLastSyncOk());
        source.setLastSyncMsg(entity.getLastSyncMsg());
        source.setCreatedBy(entity.getCreatedBy());
        source.setCreateTime(entity.getCreateTime());
        source.setUpdatedBy(entity.getUpdatedBy());
        source.setUpdateTime(entity.getUpdateTime());
        return source;
    }
}

package com.agenthub.admin.infra.persistence;

import com.agenthub.admin.domain.model.App;
import com.agenthub.admin.domain.model.AppCredential;
import com.agenthub.admin.domain.model.AppQuery;
import com.agenthub.admin.domain.model.DataScopeSpec;
import com.agenthub.admin.domain.repository.AppRepository;
import com.agenthub.admin.infra.persistence.entity.SysAppAgentEntity;
import com.agenthub.admin.infra.persistence.entity.SysAppEntity;
import com.agenthub.admin.infra.persistence.mapper.AppAgentMapper;
import com.agenthub.admin.infra.persistence.mapper.AppMapper;
import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 应用仓储实现。所属域：admin。查询不带出 app_secret_hash。
 */
@Repository
public class AppRepositoryImpl implements AppRepository {

    private final AppMapper appMapper;
    private final AppAgentMapper appAgentMapper;

    /**
     * @param appMapper      应用表
     * @param appAgentMapper 授权表
     */
    public AppRepositoryImpl(AppMapper appMapper, AppAgentMapper appAgentMapper) {
        this.appMapper = appMapper;
        this.appAgentMapper = appAgentMapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<App> page(AppQuery query, DataScopeSpec scope) {
        long current = PageQuery.clampCurrent(query.getCurrent());
        long size = PageQuery.clampSize(query.getSize());
        IPage<SysAppEntity> page = appMapper.selectPageByScope(new Page<>(current, size), query.getName(), query.getStatus(), scope);
        List<App> records = new ArrayList<>();
        for (SysAppEntity entity : page.getRecords()) {
            records.add(toDomain(entity));
        }
        fillAgents(records);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<App> findById(Long id) {
        SysAppEntity entity = appMapper.selectById(id);
        if (entity == null) {
            return Optional.empty();
        }
        App app = toDomain(entity);
        fillAgents(List.of(app));
        return Optional.of(app);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AppCredential> findCredentialByAppKey(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return Optional.empty();
        }
        // @TableLogic 会自动追加 deleted = 0；这里只取一行，不给上层暴露 Entity
        SysAppEntity entity = appMapper.selectOne(new LambdaQueryWrapper<SysAppEntity>()
                .eq(SysAppEntity::getAppKey, appKey)
                .last("LIMIT 1"));
        if (entity == null) {
            return Optional.empty();
        }
        AppCredential credential = new AppCredential();
        credential.setAppId(entity.getId());
        credential.setAppKey(entity.getAppKey());
        credential.setName(entity.getName());
        credential.setDeptId(entity.getDeptId());
        credential.setStatus(entity.getStatus());
        credential.setSecretHash(entity.getAppSecretHash());
        return Optional.of(credential);
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(App app, String secretHash, Long operatorId) {
        SysAppEntity entity = new SysAppEntity();
        entity.setAppKey(app.getAppKey());
        entity.setAppSecretHash(secretHash);
        entity.setName(app.getName());
        entity.setDeptId(app.getDeptId());
        entity.setStatus(app.getStatus() == null ? 1 : app.getStatus());
        entity.setRemark(app.getRemark());
        entity.setCreatedBy(operatorId);
        appMapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateProfile(App app, Long operatorId) {
        appMapper.update(null, new LambdaUpdateWrapper<SysAppEntity>()
                .eq(SysAppEntity::getId, app.getId())
                .set(SysAppEntity::getName, app.getName())
                .set(SysAppEntity::getDeptId, app.getDeptId())
                .set(SysAppEntity::getStatus, app.getStatus())
                .set(SysAppEntity::getRemark, app.getRemark())
                .set(SysAppEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void updateSecret(Long id, String secretHash, Long operatorId) {
        appMapper.update(null, new LambdaUpdateWrapper<SysAppEntity>()
                .eq(SysAppEntity::getId, id)
                .set(SysAppEntity::getAppSecretHash, secretHash)
                .set(SysAppEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void logicDelete(Long id, Long operatorId) {
        appMapper.update(null, new LambdaUpdateWrapper<SysAppEntity>()
                .eq(SysAppEntity::getId, id)
                .set(SysAppEntity::getUpdatedBy, operatorId));
        appMapper.deleteById(id);
        appAgentMapper.delete(new LambdaQueryWrapper<SysAppAgentEntity>().eq(SysAppAgentEntity::getAppId, id));
    }

    /** {@inheritDoc} */
    @Override
    public void replaceAgents(Long appId, Collection<Long> agentIds) {
        appAgentMapper.delete(new LambdaQueryWrapper<SysAppAgentEntity>().eq(SysAppAgentEntity::getAppId, appId));
        if (agentIds == null) {
            return;
        }
        for (Long agentId : agentIds) {
            SysAppAgentEntity link = new SysAppAgentEntity();
            link.setAppId(appId);
            link.setAgentId(agentId);
            appAgentMapper.insert(link);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean appKeyTaken(String appKey) {
        return appMapper.countByAppKey(appKey) > 0;
    }

    private void fillAgents(List<App> apps) {
        if (apps.isEmpty()) {
            return;
        }
        List<Long> ids = apps.stream().map(App::getId).toList();
        Map<Long, App> byId = new HashMap<>();
        for (App app : apps) {
            byId.put(app.getId(), app);
        }
        List<SysAppAgentEntity> links = appAgentMapper.selectList(new LambdaQueryWrapper<SysAppAgentEntity>()
                .in(SysAppAgentEntity::getAppId, ids));
        for (SysAppAgentEntity link : links) {
            App app = byId.get(link.getAppId());
            if (app != null) {
                app.getAgentIds().add(link.getAgentId());
            }
        }
    }

    private App toDomain(SysAppEntity entity) {
        App app = new App();
        app.setId(entity.getId());
        app.setAppKey(entity.getAppKey());
        app.setName(entity.getName());
        app.setDeptId(entity.getDeptId());
        app.setDeptName(entity.getDeptName());
        app.setStatus(entity.getStatus());
        app.setRemark(entity.getRemark());
        app.setCreatedBy(entity.getCreatedBy());
        // 域模型仍用 createTime；库列已是 create_time
        app.setCreateTime(entity.getCreateTime());
        return app;
    }
}

package com.agenthub.agent.infra.persistence;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentQuery;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.infra.persistence.entity.AgentDefEntity;
import com.agenthub.agent.infra.persistence.mapper.AgentDefMapper;
import com.agenthub.common.api.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Agent 定义仓储实现。所属域：agent。Entity 不出本包。
 */
@Repository
public class AgentDefRepositoryImpl implements AgentDefRepository {

    private final AgentDefMapper mapper;

    /**
     * @param mapper agent_def
     */
    public AgentDefRepositoryImpl(AgentDefMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<AgentDef> page(AgentQuery query) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        LambdaQueryWrapper<AgentDefEntity> wrapper = new LambdaQueryWrapper<AgentDefEntity>()
                .orderByDesc(AgentDefEntity::getId);
        if (query.getStatus() != null) {
            wrapper.eq(AgentDefEntity::getStatus, query.getStatus());
        }
        if (query.getRunMode() != null) {
            wrapper.eq(AgentDefEntity::getRunMode, query.getRunMode());
        }
        String keyword = likeText(query.getKeyword());
        if (keyword != null) {
            wrapper.and(one -> one.like(AgentDefEntity::getName, keyword)
                    .or()
                    .like(AgentDefEntity::getCode, keyword));
        }
        IPage<AgentDefEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AgentDef> findById(Long id) {
        AgentDefEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AgentDef> findByIdIncludingDeleted(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        // 走自定义 SQL，绕过 TableLogic，才能拼到已删 Agent 的历史用量
        AgentDefEntity entity = mapper.selectByIdIncludingDeleted(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public List<AgentDef> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectBatchIds(ids).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public boolean existsByCode(String code, Long excludeId) {
        LambdaQueryWrapper<AgentDefEntity> wrapper = new LambdaQueryWrapper<AgentDefEntity>()
                .eq(AgentDefEntity::getCode, code);
        if (excludeId != null) {
            wrapper.ne(AgentDefEntity::getId, excludeId);
        }
        return mapper.selectCount(wrapper) > 0;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(AgentDef agent, Long operatorId) {
        AgentDefEntity entity = new AgentDefEntity();
        entity.setName(agent.getName());
        entity.setCode(agent.getCode());
        entity.setDescription(agent.getDescription());
        entity.setRunMode(agent.getRunMode());
        entity.setAllowOrchestrated(agent.getAllowOrchestrated());
        entity.setSystemPrompt(agent.getSystemPrompt());
        entity.setPromptTemplateId(agent.getPromptTemplateId());
        entity.setTemplateVersion(agent.getTemplateVersion());
        entity.setPromptOverrideJson(agent.getPromptOverrideJson());
        entity.setPromptExtra(agent.getPromptExtra());
        copyLimits(agent, entity);
        entity.setStatus(agent.getStatus());
        entity.setRemark(agent.getRemark());
        entity.setCreatedBy(operatorId);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void updateProfile(AgentDef agent, Long operatorId) {
        // 限制列显式 set，含 null，才能把「清空=跟平台」写回库
        mapper.update(null, new LambdaUpdateWrapper<AgentDefEntity>()
                .eq(AgentDefEntity::getId, agent.getId())
                .set(AgentDefEntity::getName, agent.getName())
                .set(AgentDefEntity::getCode, agent.getCode())
                .set(AgentDefEntity::getDescription, agent.getDescription())
                .set(AgentDefEntity::getRunMode, agent.getRunMode())
                .set(AgentDefEntity::getAllowOrchestrated, agent.getAllowOrchestrated())
                .set(AgentDefEntity::getSystemPrompt, agent.getSystemPrompt())
                .set(AgentDefEntity::getPromptTemplateId, agent.getPromptTemplateId())
                .set(AgentDefEntity::getTemplateVersion, agent.getTemplateVersion())
                .set(AgentDefEntity::getPromptOverrideJson, agent.getPromptOverrideJson())
                .set(AgentDefEntity::getPromptExtra, agent.getPromptExtra())
                .set(AgentDefEntity::getTimeoutMs, agent.getTimeoutMs())
                .set(AgentDefEntity::getMaxRounds, agent.getMaxRounds())
                .set(AgentDefEntity::getToolTimeoutMs, agent.getToolTimeoutMs())
                .set(AgentDefEntity::getToolMaxRetries, agent.getToolMaxRetries())
                .set(AgentDefEntity::getMaxToolCalls, agent.getMaxToolCalls())
                .set(AgentDefEntity::getLlmTimeoutMs, agent.getLlmTimeoutMs())
                .set(AgentDefEntity::getMaxLlmCalls, agent.getMaxLlmCalls())
                .set(AgentDefEntity::getConsecutiveFailAbort, agent.getConsecutiveFailAbort())
                .set(AgentDefEntity::getToolResultMaxChars, agent.getToolResultMaxChars())
                .set(AgentDefEntity::getRuntimeConfigJson, agent.getRuntimeConfigJson())
                .set(AgentDefEntity::getRemark, agent.getRemark())
                .set(AgentDefEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void updateRuntimeConfig(Long id, String runtimeConfigJson, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentDefEntity>()
                .eq(AgentDefEntity::getId, id)
                .set(AgentDefEntity::getRuntimeConfigJson, runtimeConfigJson)
                .set(AgentDefEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public void updateStatus(Long id, int status, Long operatorId) {        mapper.update(null, new LambdaUpdateWrapper<AgentDefEntity>()
                .eq(AgentDefEntity::getId, id)
                .set(AgentDefEntity::getStatus, status)
                .set(AgentDefEntity::getUpdatedBy, operatorId));
    }

    /**
     * {@inheritDoc}
     * 先记下操作人，再走逻辑删除，避免只改 deleted 时丢更新人。
     */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<AgentDefEntity>()
                .eq(AgentDefEntity::getId, id)
                .set(AgentDefEntity::getUpdatedBy, operatorId));
        mapper.deleteById(id);
    }

    /**
     * MySQL 默认用反斜杠转义 LIKE。去掉用户输入里的通配符含义。
     */
    private String likeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private AgentDef toDomain(AgentDefEntity entity) {
        AgentDef agent = new AgentDef();
        agent.setId(entity.getId());
        agent.setName(entity.getName());
        agent.setCode(entity.getCode());
        agent.setDescription(entity.getDescription());
        agent.setRunMode(entity.getRunMode());
        agent.setAllowOrchestrated(entity.getAllowOrchestrated());
        agent.setSystemPrompt(entity.getSystemPrompt());
        agent.setPromptTemplateId(entity.getPromptTemplateId());
        agent.setTemplateVersion(entity.getTemplateVersion());
        agent.setPromptOverrideJson(entity.getPromptOverrideJson());
        agent.setPromptExtra(entity.getPromptExtra());
        agent.setTimeoutMs(entity.getTimeoutMs());
        agent.setMaxRounds(entity.getMaxRounds());
        agent.setToolTimeoutMs(entity.getToolTimeoutMs());
        agent.setToolMaxRetries(entity.getToolMaxRetries());
        agent.setMaxToolCalls(entity.getMaxToolCalls());
        agent.setLlmTimeoutMs(entity.getLlmTimeoutMs());
        agent.setMaxLlmCalls(entity.getMaxLlmCalls());
        agent.setConsecutiveFailAbort(entity.getConsecutiveFailAbort());
        agent.setToolResultMaxChars(entity.getToolResultMaxChars());
        agent.setRuntimeConfigJson(entity.getRuntimeConfigJson());
        agent.setStatus(entity.getStatus());
        // 普通查询走 @TableLogic 只会是 0；含删查询才可能为 1
        agent.setDeleted(entity.getDeleted());
        agent.setRemark(entity.getRemark());
        agent.setCreatedBy(entity.getCreatedBy());
        // 域/API 仍用 createTime；库列已是 create_time
        agent.setCreateTime(entity.getCreateTime());
        agent.setUpdatedBy(entity.getUpdatedBy());
        agent.setUpdateTime(entity.getUpdateTime());
        return agent;
    }

    private void copyLimits(AgentDef agent, AgentDefEntity entity) {
        entity.setTimeoutMs(agent.getTimeoutMs());
        entity.setMaxRounds(agent.getMaxRounds());
        entity.setToolTimeoutMs(agent.getToolTimeoutMs());
        entity.setToolMaxRetries(agent.getToolMaxRetries());
        entity.setMaxToolCalls(agent.getMaxToolCalls());
        entity.setLlmTimeoutMs(agent.getLlmTimeoutMs());
        entity.setMaxLlmCalls(agent.getMaxLlmCalls());
        entity.setConsecutiveFailAbort(agent.getConsecutiveFailAbort());
        entity.setToolResultMaxChars(agent.getToolResultMaxChars());
        entity.setRuntimeConfigJson(agent.getRuntimeConfigJson());
    }
}

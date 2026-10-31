package com.agenthub.runtime.infra.persistence;

import com.agenthub.common.api.PageResult;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.infra.persistence.entity.RunSessionEntity;
import com.agenthub.runtime.infra.persistence.mapper.RunSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 试跑会话仓储实现。所属域：runtime。Entity 不出本包。
 */
@Repository
public class RunSessionRepositoryImpl implements RunSessionRepository {

    private final RunSessionMapper mapper;

    /**
     * @param mapper agent_run_session
     */
    public RunSessionRepositoryImpl(RunSessionMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<RunSession> page(SessionQuery query) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        // 近期会话按开始时间倒序。id 只作同一秒内的次序
        LambdaQueryWrapper<RunSessionEntity> wrapper = new LambdaQueryWrapper<RunSessionEntity>()
                .orderByDesc(RunSessionEntity::getStartTime)
                .orderByDesc(RunSessionEntity::getId);
        if (query.getUserId() != null) {
            wrapper.eq(RunSessionEntity::getUserId, query.getUserId());
        }
        if (query.getAgentId() != null) {
            wrapper.eq(RunSessionEntity::getAgentId, query.getAgentId());
        }
        // 调用追踪左栏：预览模糊，纯数字同时能点中会话 id。试跑不传 keyword
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String keyword = query.getKeyword().trim();
            Long matchedId = sessionId(keyword);
            if (matchedId == null) {
                wrapper.like(RunSessionEntity::getLastMsgPreview, keyword);
            } else {
                wrapper.and(nested -> nested.like(RunSessionEntity::getLastMsgPreview, keyword)
                        .or()
                        .eq(RunSessionEntity::getId, matchedId));
            }
        }
        IPage<RunSessionEntity> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public List<RunSession> listRunning(Long userId, Long agentId) {
        LambdaQueryWrapper<RunSessionEntity> wrapper = new LambdaQueryWrapper<RunSessionEntity>()
                .eq(RunSessionEntity::getStatus, RunStatus.RUNNING.name())
                .orderByAsc(RunSessionEntity::getId);
        if (userId != null) {
            wrapper.eq(RunSessionEntity::getUserId, userId);
        }
        if (agentId != null) {
            wrapper.eq(RunSessionEntity::getAgentId, agentId);
        }
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RunSession> findById(Long id) {
        RunSessionEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(RunSession session, Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        RunSessionEntity entity = new RunSessionEntity();
        entity.setAgentId(session.getAgentId());
        entity.setRunMode(session.getRunMode());
        entity.setUserId(session.getUserId());
        entity.setStatus(session.getStatus());
        entity.setStartTime(session.getStartTime() == null ? now : session.getStartTime());
        entity.setEndTime(session.getEndTime());
        entity.setMessageCount(session.getMessageCount() == null ? 0 : session.getMessageCount());
        entity.setLastMsgPreview(session.getLastMsgPreview());
        entity.setErrorSummary(session.getErrorSummary());
        entity.setCreatedBy(operatorId);
        entity.setCreateTime(now);
        entity.setUpdatedBy(operatorId);
        entity.setUpdateTime(now);
        mapper.insert(entity);
        return entity.getId();
    }

    /**
     * 多轮推进只改状态、计数、预览和 update_time。
     * end_time 仅在取消等结案传入非空时写入，避免每轮把 null 塞进 SET 干扰 update_time 刷新。
     */
    @Override
    public void updateOutcome(RunSession session, Long operatorId) {
        LambdaUpdateWrapper<RunSessionEntity> wrapper = new LambdaUpdateWrapper<RunSessionEntity>()
                .eq(RunSessionEntity::getId, session.getId())
                .set(RunSessionEntity::getStatus, session.getStatus())
                .set(RunSessionEntity::getMessageCount, session.getMessageCount())
                .set(RunSessionEntity::getLastMsgPreview, session.getLastMsgPreview())
                .set(RunSessionEntity::getErrorSummary, session.getErrorSummary())
                .set(RunSessionEntity::getUpdatedBy, operatorId)
                .set(RunSessionEntity::getUpdateTime, LocalDateTime.now());
        // 终态结案才落结束时间；进行中不碰 end_time
        if (session.getEndTime() != null) {
            wrapper.set(RunSessionEntity::getEndTime, session.getEndTime());
        }
        mapper.update(null, wrapper);
    }

    /** {@inheritDoc} */
    @Override
    public void updateTitle(Long id, String title, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<RunSessionEntity>()
                .eq(RunSessionEntity::getId, id)
                .set(RunSessionEntity::getLastMsgPreview, title)
                .set(RunSessionEntity::getUpdatedBy, operatorId)
                .set(RunSessionEntity::getUpdateTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public void resetMessages(Long id, Long operatorId) {
        // UpdateWrapper 会把 end_time 写成 NULL。实体更新策略会跳过 null，所以不走实体。
        mapper.update(null, new LambdaUpdateWrapper<RunSessionEntity>()
                .eq(RunSessionEntity::getId, id)
                .set(RunSessionEntity::getStatus, RunStatus.RUNNING.name())
                .set(RunSessionEntity::getMessageCount, 0)
                .set(RunSessionEntity::getErrorSummary, "")
                .set(RunSessionEntity::getEndTime, null)
                .set(RunSessionEntity::getUpdatedBy, operatorId)
                .set(RunSessionEntity::getUpdateTime, LocalDateTime.now()));
    }

    /** {@inheritDoc} */
    @Override
    public void logicDelete(Long id) {
        mapper.deleteById(id);
    }

    /**
     * 只有整段都是数字时才当成会话 id。过长的数字不当 id，避免把预览里的长串误判。
     */
    private Long sessionId(String keyword) {
        if (keyword.length() > 18 || !keyword.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.valueOf(keyword);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private RunSession toDomain(RunSessionEntity entity) {
        RunSession session = new RunSession();
        session.setId(entity.getId());
        session.setAgentId(entity.getAgentId());
        session.setRunMode(entity.getRunMode());
        session.setUserId(entity.getUserId());
        session.setStatus(entity.getStatus());
        // 域/API 仍用 startTime/endTime/createTime；库列已是 start_time/end_time/create_time
        session.setStartTime(entity.getStartTime());
        session.setEndTime(entity.getEndTime());
        session.setMessageCount(entity.getMessageCount());
        session.setLastMsgPreview(entity.getLastMsgPreview());
        session.setErrorSummary(entity.getErrorSummary());
        session.setCreatedBy(entity.getCreatedBy());
        session.setCreateTime(entity.getCreateTime());
        session.setUpdatedBy(entity.getUpdatedBy());
        session.setUpdateTime(entity.getUpdateTime());
        return session;
    }
}

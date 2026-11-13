package com.agenthub.runtime.infra.persistence;

import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.runtime.domain.model.EntryChannel;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.SessionQuery;
import com.agenthub.runtime.domain.model.TitleSource;
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
        long current = PageQuery.clampCurrent(query.getCurrent());
        long size = PageQuery.clampSize(query.getSize());
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
        // 虚拟根左栏：按方案过滤，和按 Agent 过滤同一套分页与账号隔离
        if (query.getMaId() != null) {
            wrapper.eq(RunSessionEntity::getMaDefId, query.getMaId());
        }
        // 入口渠道隔离：工作台与调试左栏各查各的，避免同一条会话出现在两处
        if (query.getEntryChannel() != null && !query.getEntryChannel().isBlank()) {
            wrapper.eq(RunSessionEntity::getEntryChannel, EntryChannel.normalize(query.getEntryChannel()));
        }
        // 调用追踪左栏：标题与预览都参与模糊匹配，纯数字同时能点中会话 id。试跑不传 keyword
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            String keyword = query.getKeyword().trim();
            Long matchedId = sessionId(keyword);
            if (matchedId == null) {
                wrapper.and(nested -> nested.like(RunSessionEntity::getSessionTitle, keyword)
                        .or()
                        .like(RunSessionEntity::getLastMsgPreview, keyword));
            } else {
                wrapper.and(nested -> nested.like(RunSessionEntity::getSessionTitle, keyword)
                        .or()
                        .like(RunSessionEntity::getLastMsgPreview, keyword)
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
        entity.setMaDefId(session.getMaDefId());
        // 渠道空值回落 DEBUG：调试入口的历史调用方不用逐个补参数
        entity.setEntryChannel(EntryChannel.normalize(session.getEntryChannel()));
        entity.setReceptionMaId(session.getReceptionMaId());
        entity.setStickyRouteKey(session.getStickyRouteKey());
        entity.setStickyAgentId(session.getStickyAgentId());
        entity.setRunMode(session.getRunMode());
        entity.setUserId(session.getUserId());
        entity.setStatus(session.getStatus());
        entity.setStartTime(session.getStartTime() == null ? now : session.getStartTime());
        entity.setEndTime(session.getEndTime());
        entity.setMessageCount(session.getMessageCount() == null ? 0 : session.getMessageCount());
        entity.setSessionTitle(session.getSessionTitle());
        // 新会话还没有标题：来源先落 AUTO（列默认值），而不是显式写 NULL——
        // 插入时显式 NULL 会盖掉列默认值，后面的「非 MANUAL」判断虽兼容 NULL，但脏数据没必要
        entity.setTitleSource(session.getTitleSource() == null ? TitleSource.AUTO.name() : session.getTitleSource());
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
    public void updateTitle(Long id, String title, String titleSource, Long operatorId) {
        mapper.update(null, new LambdaUpdateWrapper<RunSessionEntity>()
                .eq(RunSessionEntity::getId, id)
                .set(RunSessionEntity::getSessionTitle, title)
                .set(RunSessionEntity::getTitleSource, titleSource)
                .set(RunSessionEntity::getUpdatedBy, operatorId)
                .set(RunSessionEntity::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * {@inheritDoc}
     * 条件写在 WHERE 里而不是应用层判空：异步线程读到的「空标题」可能已经过期，
     * 只有数据库当下的值才算数。{@code (title_source <> 'MANUAL' OR title_source IS NULL)}
     * 兼容历史行（该列是后加的，旧行可能为 NULL）。
     */
    @Override
    public boolean updateAutoTitleIfAbsent(Long id, String title, Long operatorId) {
        int affected = mapper.update(null, new LambdaUpdateWrapper<RunSessionEntity>()
                .eq(RunSessionEntity::getId, id)
                .and(scope -> scope.isNull(RunSessionEntity::getSessionTitle)
                        .or()
                        .eq(RunSessionEntity::getSessionTitle, ""))
                .and(source -> source.isNull(RunSessionEntity::getTitleSource)
                        .or()
                        .ne(RunSessionEntity::getTitleSource, TitleSource.MANUAL.name()))
                .set(RunSessionEntity::getSessionTitle, title)
                .set(RunSessionEntity::getTitleSource, TitleSource.AUTO.name())
                .set(RunSessionEntity::getUpdatedBy, operatorId)
                .set(RunSessionEntity::getUpdateTime, LocalDateTime.now()));
        return affected > 0;
    }

    /** {@inheritDoc} */
    @Override
    public void updateStickyRoute(Long id, String routeKey, Long agentId, Long operatorId) {
        // 显式 set null：清空多意图轮留下的线路，不能靠实体更新策略（它会跳过 null）
        mapper.update(null, new LambdaUpdateWrapper<RunSessionEntity>()
                .eq(RunSessionEntity::getId, id)
                .set(RunSessionEntity::getStickyRouteKey, routeKey)
                .set(RunSessionEntity::getStickyAgentId, agentId)
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
     * {@inheritDoc}
     * WHERE 同时卡住 status 与 update_time：启动扫描和正在跑的新请求可能交错，
     * 只有「扫到之后这行没再被推进」才允许收成取消。
     */
    @Override
    public boolean interruptIfUnchanged(Long id, LocalDateTime expectedUpdateTime, LocalDateTime endedAt) {
        if (id == null || endedAt == null) {
            return false;
        }
        LambdaUpdateWrapper<RunSessionEntity> wrapper = new LambdaUpdateWrapper<RunSessionEntity>()
                .eq(RunSessionEntity::getId, id)
                .eq(RunSessionEntity::getStatus, RunStatus.RUNNING.name())
                .set(RunSessionEntity::getStatus, RunStatus.CANCELLED.name())
                .set(RunSessionEntity::getEndTime, endedAt)
                .set(RunSessionEntity::getUpdatedBy, 0L)
                .set(RunSessionEntity::getUpdateTime, endedAt);
        // 读到的更新时间为空时必须用 IS NULL，eq(null) 在 SQL 里对不上
        if (expectedUpdateTime == null) {
            wrapper.isNull(RunSessionEntity::getUpdateTime);
        } else {
            wrapper.eq(RunSessionEntity::getUpdateTime, expectedUpdateTime);
        }
        return mapper.update(null, wrapper) > 0;
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
        session.setMaDefId(entity.getMaDefId());
        session.setEntryChannel(entity.getEntryChannel());
        session.setReceptionMaId(entity.getReceptionMaId());
        session.setStickyRouteKey(entity.getStickyRouteKey());
        session.setStickyAgentId(entity.getStickyAgentId());
        session.setRunMode(entity.getRunMode());
        session.setUserId(entity.getUserId());
        session.setStatus(entity.getStatus());
        // 域/API 仍用 startTime/endTime/createTime；库列已是 start_time/end_time/create_time
        session.setStartTime(entity.getStartTime());
        session.setEndTime(entity.getEndTime());
        session.setMessageCount(entity.getMessageCount());
        session.setSessionTitle(entity.getSessionTitle());
        session.setTitleSource(entity.getTitleSource());
        session.setLastMsgPreview(entity.getLastMsgPreview());
        session.setErrorSummary(entity.getErrorSummary());
        session.setCreatedBy(entity.getCreatedBy());
        session.setCreateTime(entity.getCreateTime());
        session.setUpdatedBy(entity.getUpdatedBy());
        session.setUpdateTime(entity.getUpdateTime());
        return session;
    }
}

package com.agenthub.runtime.infra.persistence;

import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.infra.persistence.entity.RunEventEntity;
import com.agenthub.runtime.infra.persistence.mapper.RunEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 试跑事件仓储实现。所属域：runtime。摘要在入库前已经截断，这里不再改写。
 */
@Repository
public class RunEventRepositoryImpl implements RunEventRepository {

    private final RunEventMapper mapper;

    /**
     * @param mapper agent_run_event
     */
    public RunEventRepositoryImpl(RunEventMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<RunEvent> listBySessionId(Long sessionId) {
        return mapper.selectList(new LambdaQueryWrapper<RunEventEntity>()
                        .eq(RunEventEntity::getSessionId, sessionId)
                        .orderByAsc(RunEventEntity::getSeq)
                        .orderByAsc(RunEventEntity::getId))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public int nextSeq(Long sessionId) {
        RunEventEntity last = mapper.selectOne(new LambdaQueryWrapper<RunEventEntity>()
                .eq(RunEventEntity::getSessionId, sessionId)
                .orderByDesc(RunEventEntity::getSeq)
                .last("limit 1"));
        if (last == null || last.getSeq() == null) {
            return 1;
        }
        return last.getSeq() + 1;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(RunEvent event) {
        RunEventEntity entity = new RunEventEntity();
        entity.setSessionId(event.getSessionId());
        entity.setSeq(event.getSeq());
        entity.setEventType(event.getEventType());
        entity.setSummary(event.getSummary());
        entity.setContentText(event.getContentText());
        entity.setTokenPrompt(event.getTokenPrompt());
        entity.setTokenCompletion(event.getTokenCompletion());
        entity.setCreateTime(event.getCreateTime() == null ? LocalDateTime.now() : event.getCreateTime());
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void deleteBySessionId(Long sessionId) {
        mapper.delete(new LambdaQueryWrapper<RunEventEntity>()
                .eq(RunEventEntity::getSessionId, sessionId));
    }

    private RunEvent toDomain(RunEventEntity entity) {
        RunEvent event = new RunEvent();
        event.setId(entity.getId());
        event.setSessionId(entity.getSessionId());
        event.setSeq(entity.getSeq());
        event.setEventType(entity.getEventType());
        event.setSummary(entity.getSummary());
        event.setContentText(entity.getContentText());
        event.setTokenPrompt(entity.getTokenPrompt());
        event.setTokenCompletion(entity.getTokenCompletion());
        // 域/API 仍用 createTime；库列已是 create_time
        event.setCreateTime(entity.getCreateTime());
        return event;
    }
}

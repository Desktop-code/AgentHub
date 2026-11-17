package com.agenthub.runtime.infra.persistence;

import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.infra.persistence.entity.RunEventEntity;
import com.agenthub.runtime.infra.persistence.mapper.RunEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

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

    /**
     * {@inheritDoc}
     * 每批最多 200 个会话，避免 IN 列表过长。
     */
    @Override
    public List<RunEvent> listClosureMarks(Collection<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = sessionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<RunEvent> marks = new ArrayList<>();
        int batch = 200;
        for (int from = 0; from < ids.size(); from += batch) {
            List<Long> slice = ids.subList(from, Math.min(from + batch, ids.size()));
            mapper.listClosureMarks(slice).stream().map(this::toDomain).forEach(marks::add);
        }
        return marks;
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

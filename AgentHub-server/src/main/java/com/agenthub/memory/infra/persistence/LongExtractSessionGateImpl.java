package com.agenthub.memory.infra.persistence;

import com.agenthub.memory.domain.model.LongExtractMark;
import com.agenthub.memory.domain.repository.LongExtractSessionGate;
import com.agenthub.memory.infra.persistence.entity.MemorySessionExtractEntity;
import com.agenthub.memory.infra.persistence.mapper.MemorySessionExtractMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 会话级批抽幂等闸门实现。所属域：memory。
 * 以 session_id 为主键 upsert；读侧只认 DONE/FAILED/SKIPPED 为已处理。
 */
@Repository
public class LongExtractSessionGateImpl implements LongExtractSessionGate {

    private final MemorySessionExtractMapper mapper;

    /**
     * @param mapper memory_session_extract
     */
    public LongExtractSessionGateImpl(MemorySessionExtractMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isProcessed(long sessionId) {
        MemorySessionExtractEntity row = mapper.selectById(sessionId);
        if (row == null || row.getStatus() == null) {
            return false;
        }
        String status = row.getStatus().trim();
        return LongExtractMark.DONE.name().equals(status)
                || LongExtractMark.FAILED.name().equals(status)
                || LongExtractMark.SKIPPED.name().equals(status);
    }

    /** {@inheritDoc} */
    @Override
    public void complete(long sessionId, LongExtractMark mark, String reason) {
        if (mark == null) {
            return;
        }
        MemorySessionExtractEntity row = new MemorySessionExtractEntity();
        row.setSessionId(sessionId);
        row.setStatus(mark.name());
        row.setReason(cut(reason, 128));
        row.setUpdateTime(LocalDateTime.now());
        // 已有行则更新，保证同一次失败路径可覆盖
        if (mapper.selectById(sessionId) == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void clear(long sessionId) {
        mapper.deleteById(sessionId);
    }

    private static String cut(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}

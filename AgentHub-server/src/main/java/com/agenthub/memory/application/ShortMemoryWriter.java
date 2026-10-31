package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.MemoryScope;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.MemoryTexts;
import com.agenthub.memory.domain.service.ShortMemoryCache;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * SHORT 双写。所属域：memory。
 * 先 Redis（带 TTL）再 MySQL。MySQL 失败把 Redis 恢复成写入前的值；Redis 不可用则根本不写库。
 */
@Service
public class ShortMemoryWriter {

    private final ShortMemoryCache cache;
    private final MemoryItemRepository items;

    /**
     * @param cache 热路径
     * @param items 耐久与列表
     */
    public ShortMemoryWriter(ShortMemoryCache cache, MemoryItemRepository items) {
        this.cache = cache;
        this.items = items;
    }

    /**
     * @param policy     已钳制的策略
     * @param draft      新的 SHORT，必须有 agentId 和 sessionId
     * @param operatorId 操作人
     * @return 入库后的条目；相同哈希已存在时返回那一条
     * @throws BizException 策略关闭、Redis 不可用或数据库失败
     */
    public MemoryItem append(MemoryPolicy policy, MemoryItem draft, long operatorId) {
        requireShort(policy, draft);
        MemoryTexts.rejectSecret(draft.getContent());
        draft.setContentHash(MemoryTexts.hash(draft.getContent()));
        draft.setScope(MemoryScope.SHORT.name());
        if (draft.getStatus() == null) {
            draft.setStatus(1);
        }
        if (draft.getExpiredTime() == null) {
            draft.setExpiredTime(LocalDateTime.now().plusSeconds(Math.max(policy.getShortDefaultTtlSec(), 1)));
        }
        Hot hot = load(draft.getAgentId(), draft.getSessionId());
        List<MemoryItem> live = alive(hot.rows());
        for (MemoryItem existing : live) {
            if (draft.getContentHash().equals(existing.getContentHash())) {
                rewrite(draft, hot, live);
                return existing;
            }
        }
        List<Long> evict = trim(live, policy.getMaxShortItems());
        live.add(draft);
        // 先占住 Redis。库失败时恢复写入前的热数据，避免只写了一侧还报成功
        rewrite(draft, hot, live);
        Long id = null;
        try {
            id = items.insert(draft, operatorId);
            draft.setId(id);
            for (Long old : evict) {
                items.logicalDelete(old, operatorId);
            }
        } catch (RuntimeException ex) {
            if (id != null) {
                items.logicalDelete(id, operatorId);
            }
            restore(draft, hot);
            throw new BizException(ErrorCode.BAD_REQUEST, "短期记忆写入数据库失败");
        }
        try {
            rewrite(draft, hot, live);
        } catch (RuntimeException ex) {
            // 库已提交。删掉 key，下次读走 miss 回补，不把旧列表盖回去
            cache.delete(draft.getAgentId(), draft.getSessionId());
            throw redisDown(ex);
        }
        return draft;
    }

    /**
     * 控制台改 SHORT。先覆盖 Redis 再更新数据库。
     *
     * @param updated    已改好的条目，含 id
     * @param operatorId 操作人
     */
    public void update(MemoryItem updated, long operatorId) {
        requireIds(updated);
        MemoryTexts.rejectSecret(updated.getContent());
        updated.setContentHash(MemoryTexts.hash(updated.getContent()));
        Hot hot = load(updated.getAgentId(), updated.getSessionId());
        List<MemoryItem> live = alive(hot.rows());
        boolean found = false;
        for (int i = 0; i < live.size(); i++) {
            if (updated.getId().equals(live.get(i).getId())) {
                live.set(i, updated);
                found = true;
            }
        }
        if (!found && updated.getStatus() != null && updated.getStatus() == 1) {
            live.add(updated);
        }
        if (updated.getStatus() == null || updated.getStatus() != 1) {
            live.removeIf(one -> updated.getId().equals(one.getId()));
        }
        rewrite(updated, hot, live);
        try {
            items.update(updated, operatorId);
        } catch (RuntimeException ex) {
            restore(updated, hot);
            throw new BizException(ErrorCode.BAD_REQUEST, "短期记忆写入数据库失败");
        }
    }

    /**
     * 先从热列表拿掉，再逻辑删除。数据库失败则把 Redis 放回去。
     *
     * @param existing   当前行
     * @param operatorId 操作人
     */
    public void remove(MemoryItem existing, long operatorId) {
        requireIds(existing);
        Hot hot = load(existing.getAgentId(), existing.getSessionId());
        List<MemoryItem> live = alive(hot.rows());
        live.removeIf(one -> existing.getId().equals(one.getId()));
        rewrite(existing, hot, live);
        try {
            items.logicalDelete(existing.getId(), operatorId);
        } catch (RuntimeException ex) {
            restore(existing, hot);
            throw new BizException(ErrorCode.BAD_REQUEST, "短期记忆写入数据库失败");
        }
    }

    private void requireShort(MemoryPolicy policy, MemoryItem item) {
        if (policy.getEnableShort() == null || policy.getEnableShort() != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "短期记忆已关闭");
        }
        requireIds(item);
    }

    private void requireIds(MemoryItem item) {
        if (item.getAgentId() == null || item.getSessionId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "短期记忆必须指定 Agent 和会话");
        }
    }

    private Hot load(long agentId, long sessionId) {
        try {
            Optional<List<MemoryItem>> cached = cache.read(agentId, sessionId);
            if (cached.isPresent()) {
                return new Hot(true, new ArrayList<>(cached.get()));
            }
        } catch (RuntimeException ex) {
            throw redisDown(ex);
        }
        List<MemoryItem> fromDb = items.listActiveShort(agentId, sessionId, LocalDateTime.now());
        return new Hot(false, new ArrayList<>(fromDb));
    }

    private void rewrite(MemoryItem anchor, Hot hot, List<MemoryItem> live) {
        int ttl = ttl(anchor, live);
        try {
            cache.write(anchor.getAgentId(), anchor.getSessionId(), live, ttl);
        } catch (RuntimeException ex) {
            throw redisDown(ex);
        }
    }

    private void restore(MemoryItem anchor, Hot hot) {
        try {
            if (!hot.hit()) {
                cache.delete(anchor.getAgentId(), anchor.getSessionId());
                return;
            }
            cache.write(anchor.getAgentId(), anchor.getSessionId(), hot.rows(), ttl(anchor, hot.rows()));
        } catch (RuntimeException ex) {
            cache.delete(anchor.getAgentId(), anchor.getSessionId());
        }
    }

    private int ttl(MemoryItem anchor, List<MemoryItem> live) {
        int ttl = 1;
        if (anchor.getExpiredTime() != null) {
            ttl = MemoryTexts.ttlSeconds(anchor.getExpiredTime(), 1);
        }
        for (MemoryItem item : live) {
            if (item.getExpiredTime() != null) {
                ttl = Math.max(ttl, MemoryTexts.ttlSeconds(item.getExpiredTime(), ttl));
            }
        }
        return ttl;
    }

    private List<MemoryItem> alive(List<MemoryItem> rows) {
        LocalDateTime now = LocalDateTime.now();
        List<MemoryItem> live = new ArrayList<>();
        for (MemoryItem item : rows) {
            if (item.getStatus() != null && item.getStatus() != 1) {
                continue;
            }
            if (item.getExpiredTime() != null && !item.getExpiredTime().isAfter(now)) {
                continue;
            }
            live.add(item);
        }
        live.sort(Comparator.comparing(MemoryItem::getId, Comparator.nullsLast(Long::compareTo)));
        return live;
    }

    private List<Long> trim(List<MemoryItem> live, Integer maxItems) {
        int max = maxItems == null || maxItems < 1 ? 1 : maxItems;
        List<Long> evict = new ArrayList<>();
        while (live.size() >= max) {
            MemoryItem oldest = live.remove(0);
            if (oldest.getId() != null) {
                evict.add(oldest.getId());
            }
        }
        return evict;
    }

    private BizException redisDown(RuntimeException ex) {
        if (ex instanceof BizException biz) {
            return biz;
        }
        if (ex instanceof DataAccessException || ex instanceof IllegalStateException) {
            return new BizException(ErrorCode.BAD_REQUEST, "Redis 不可用，短期记忆写入失败");
        }
        return new BizException(ErrorCode.BAD_REQUEST, "Redis 不可用，短期记忆写入失败");
    }

    /**
     * 写入前的热数据。hit=false 时回滚要删 key，不能把数据库列表写回。
     *
     * @param hit  Redis 是否命中
     * @param rows 命中时的原列表；未命中时只是数据库快照，不作为回滚内容
     */
    private record Hot(boolean hit, List<MemoryItem> rows) {
    }
}

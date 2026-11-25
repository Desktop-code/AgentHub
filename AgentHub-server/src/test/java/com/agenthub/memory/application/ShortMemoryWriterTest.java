package com.agenthub.memory.application;

import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.MemoryQuery;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.ShortMemoryCache;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SHORT 先写 Redis。数据库失败要回到写入前的热数据；Redis 失败则不入库。
 */
class ShortMemoryWriterTest {

    @Test
    void mysqlFailureRestoresRedis() {
        MemoryItem old = item("旧的");
        old.setId(3L);
        FakeCache cache = new FakeCache();
        cache.hit = List.of(old);
        FakeRepo repo = new FakeRepo();
        repo.failInsert = true;
        ShortMemoryWriter writer = new ShortMemoryWriter(cache, repo);
        MemoryItem draft = item("新的");
        assertThrows(BizException.class, () -> writer.append(policy(), draft, 1L));
        assertEquals(1, cache.rows.size());
        assertEquals("旧的", cache.rows.get(0).getContent());
        assertEquals(0, repo.inserted);
    }

    @Test
    void redisDownSkipsDatabase() {
        FakeCache cache = new FakeCache();
        cache.failWrite = true;
        FakeRepo repo = new FakeRepo();
        ShortMemoryWriter writer = new ShortMemoryWriter(cache, repo);
        BizException ex = assertThrows(BizException.class, () -> writer.append(policy(), item("新的"), 1L));
        assertTrue(ex.getMessage().contains("Redis"));
        assertEquals(0, repo.inserted);
    }

    private static MemoryPolicy policy() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableShort(1);
        policy.setMaxShortItems(20);
        policy.setShortDefaultTtlSec(3600);
        return policy;
    }

    private static MemoryItem item(String content) {
        MemoryItem item = new MemoryItem();
        item.setAgentId(1L);
        item.setSessionId(9L);
        item.setScope("SHORT");
        item.setContent(content);
        item.setStatus(1);
        return item;
    }

    private static final class FakeCache implements ShortMemoryCache {
        private List<MemoryItem> hit;
        private List<MemoryItem> rows = new ArrayList<>();
        private boolean failWrite;

        @Override
        public Optional<List<MemoryItem>> read(long agentId, long sessionId) {
            return hit == null ? Optional.empty() : Optional.of(hit);
        }

        @Override
        public void write(long agentId, long sessionId, List<MemoryItem> items, int ttlSec) {
            if (failWrite) {
                throw new IllegalStateException("down");
            }
            rows = new ArrayList<>(items);
        }

        @Override
        public void delete(long agentId, long sessionId) {
            rows = new ArrayList<>();
            hit = null;
        }
    }

    private static final class FakeRepo implements MemoryItemRepository {
        private boolean failInsert;
        private int inserted;

        @Override
        public Long insert(MemoryItem item, Long operatorId) {
            if (failInsert) {
                throw new IllegalStateException("db");
            }
            inserted++;
            return 8L;
        }

        @Override
        public PageResult<MemoryItem> page(MemoryQuery query) {
            return PageResult.of(0, 1, 20, List.of());
        }

        @Override
        public Optional<MemoryItem> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public Optional<MemoryItem> findActiveByHash(Long agentId, Long userId, String scope, String hash) {
            return Optional.empty();
        }

        @Override
        public Optional<MemoryItem> findActiveByFactKey(Long agentId, Long userId, String scope, String factKey) {
            return Optional.empty();
        }

        @Override
        public List<MemoryItem> findActiveByFactKeys(Long agentId, Long userId, String scope,
                                                     java.util.Collection<String> factKeys) {
            // 本用例只覆盖 SHORT 写入，不涉及同族合并
            return List.of();
        }

        @Override
        public List<MemoryItem> listActiveShort(Long agentId, Long sessionId, LocalDateTime now) {
            return List.of();
        }

        @Override
        public List<MemoryItem> listActive(Long agentId, Long userId, String scope, int limit, LocalDateTime now) {
            return List.of();
        }

        @Override
        public List<MemoryItem> listActiveByIds(java.util.Collection<Long> memoryIds, String scope, LocalDateTime now) {
            return List.of();
        }

        @Override
        public int countActive(Long agentId, Long userId, String scope) {
            return 0;
        }

        @Override
        public List<Long> evictOverflow(Long agentId, Long userId, String scope, int keep, Long operatorId) {
            return List.of();
        }

        @Override
        public void update(MemoryItem item, Long operatorId) {
        }

        @Override
        public void logicalDelete(Long id, Long operatorId) {
        }

        @Override
        public List<Long> purgeExpired(LocalDateTime now, int limit) {
            return List.of();
        }

        @Override
        public int markVectorSync(java.util.Collection<Long> memoryIds,
                                  com.agenthub.memory.domain.model.MemoryVectorSyncStatus status,
                                  int failCount, String error) {
            return 0;
        }

        @Override
        public int markVectorSyncStatus(java.util.Collection<Long> memoryIds,
                                        com.agenthub.memory.domain.model.MemoryVectorSyncStatus status) {
            return 0;
        }

        @Override
        public int bumpVectorSyncFail(java.util.Collection<Long> memoryIds,
                                      com.agenthub.memory.domain.model.MemoryVectorSyncStatus status,
                                      String error) {
            return 0;
        }

        @Override
        public List<Long> listVectorDeleteCandidates(LocalDateTime now, int limit, int maxFailCount) {
            return List.of();
        }

        @Override
        public List<MemoryItem> listVectorSyncCandidates(Long agentId, int limit, int maxFailCount,
                                                         LocalDateTime now) {
            return List.of();
        }

        @Override
        public List<Long> listLongAgentIds() {
            return List.of();
        }

        @Override
        public int countVectorSyncCandidates(int maxFailCount, LocalDateTime now) {
            return 0;
        }

        @Override
        public int requeueValidLong(LocalDateTime now) {
            // 受控重建专用；本用例不覆盖重建路径
            return 0;
        }
    }
}

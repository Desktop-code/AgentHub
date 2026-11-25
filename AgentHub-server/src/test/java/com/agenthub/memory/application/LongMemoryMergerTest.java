package com.agenthub.memory.application;

import com.agenthub.common.api.PageResult;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryQuery;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.FactKeyNormalizer;
import com.agenthub.memory.domain.service.MemoryTexts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * LONG 同键合并：同 fact_key 更新不双插；不同 key 可多条；缺 key 抽取跳过。
 */
class LongMemoryMergerTest {

    private FakeRepo repo;
    private MemoryVectorHook vectors;
    private LongMemoryMerger merger;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        vectors = mock(MemoryVectorHook.class);
        merger = new LongMemoryMerger(repo, new FactKeyNormalizer(Map.of()), vectors);
    }

    @Test
    void sameResidenceKeyMergesToOneRow() {
        MemoryTexts.PreferenceDraft first = draft("residence", "居住地", "用户住长沙", 60);
        MemoryTexts.PreferenceDraft second = draft("city_of_residence", "常驻城市", "用户住广州", 80);
        assertTrue(merger.upsertExtracted(1L, 2L, first, 2L).orElseThrow());
        assertEquals(Optional.of(false), merger.upsertExtracted(1L, 2L, second, 2L));
        assertEquals(1, repo.rows.size());
        MemoryItem row = repo.rows.get(0);
        assertEquals("residence_city", row.getFactKey());
        assertEquals("用户住广州", row.getContent());
        assertEquals(80, row.getImportance());
        assertEquals("常驻城市", row.getTitle());
    }

    @Test
    void differentKeysStaySeparate() {
        assertTrue(merger.upsertExtracted(1L, 2L, draft("residence_city", "城", "住长沙", 50), 2L).orElseThrow());
        assertTrue(merger.upsertExtracted(1L, 2L, draft("dietary_restriction", "忌", "不吃香菜", 70), 2L).orElseThrow());
        assertEquals(2, repo.rows.size());
    }

    @Test
    void missingFactKeySkipped() {
        assertTrue(merger.upsertExtracted(1L, 2L, draft(null, "城", "住长沙", 50), 2L).isEmpty());
        assertEquals(0, repo.rows.size());
    }

    @Test
    void identicalContentHashStillSingleRow() {
        MemoryTexts.PreferenceDraft a = draft("residence_city", "城", "用户住长沙", 40);
        MemoryTexts.PreferenceDraft b = draft("residence_city", "城2", "用户住长沙", 90);
        merger.upsertExtracted(1L, 2L, a, 2L);
        merger.upsertExtracted(1L, 2L, b, 2L);
        assertEquals(1, repo.rows.size());
        assertEquals(90, repo.rows.get(0).getImportance());
    }

    @Test
    void manualWithoutKeyUsesHashPath() {
        MemoryItem draft = new MemoryItem();
        draft.setAgentId(1L);
        draft.setUserId(2L);
        draft.setScope("LONG");
        draft.setContent("默认上海");
        draft.setContentHash(MemoryTexts.hash("默认上海"));
        draft.setImportance(50);
        draft.setStatus(1);
        MemoryItem saved = merger.upsertManual(draft, 9L);
        assertEquals(1, repo.rows.size());
        MemoryItem again = new MemoryItem();
        again.setAgentId(1L);
        again.setUserId(2L);
        again.setScope("LONG");
        again.setContent("默认上海");
        again.setContentHash(MemoryTexts.hash("默认上海"));
        again.setImportance(70);
        again.setStatus(1);
        merger.upsertManual(again, 9L);
        assertEquals(1, repo.rows.size());
        assertEquals(saved.getId(), repo.rows.get(0).getId());
        assertEquals(70, repo.rows.get(0).getImportance());
    }

    private static MemoryTexts.PreferenceDraft draft(String key, String title, String content, int importance) {
        MemoryTexts.PreferenceDraft d = new MemoryTexts.PreferenceDraft();
        d.setFactKey(key);
        d.setTitle(title);
        d.setContent(content);
        d.setImportance(importance);
        return d;
    }

    @Test
    void ttlDaysWritesExpiredTimeAndPermanentLeavesItNull() {
        MemoryTexts.PreferenceDraft shortLived = draft("has_cold", "状态", "用户这周感冒", 40);
        shortLived.setTtlDays(7);
        merger.upsertExtracted(1L, 2L, shortLived, 2L);
        assertTrue(repo.rows.get(0).getExpiredTime() != null,
                "ttl_days>0 必须写 expired_time");

        MemoryTexts.PreferenceDraft permanent = draft("residence_city", "居住地", "用户住广州", 80);
        permanent.setTtlDays(0);
        merger.upsertExtracted(1L, 2L, permanent, 2L);
        assertEquals(null, repo.rows.get(1).getExpiredTime(),
                "ttl_days=0 表示永久，expired_time 必须留空");
    }

    @Test
    void sameKeyNewTtlOverridesOldExpiryButImportanceTakesMax() {
        MemoryTexts.PreferenceDraft first = draft("has_cold", "状态", "用户这周感冒", 80);
        first.setTtlDays(7);
        merger.upsertExtracted(1L, 2L, first, 2L);
        assertTrue(repo.rows.get(0).getExpiredTime() != null);

        // 同一事实改判为永久：必须把旧的过期时间清掉，否则到期后这条会被静默丢弃
        MemoryTexts.PreferenceDraft second = draft("has_cold", "状态", "用户已康复，无长期限制", 30);
        second.setTtlDays(0);
        assertEquals(Optional.of(false), merger.upsertExtracted(1L, 2L, second, 2L));
        assertEquals(1, repo.rows.size());
        assertEquals(null, repo.rows.get(0).getExpiredTime());
        assertEquals(80, repo.rows.get(0).getImportance(), "importance 仍取 max");
        assertEquals("用户已康复，无长期限制", repo.rows.get(0).getContent());
    }

    @Test
    void manualEntryWithoutTtlKeepsExistingExpiry() {
        MemoryTexts.PreferenceDraft extracted = draft("has_cold", "状态", "用户这周感冒", 50);
        extracted.setTtlDays(7);
        merger.upsertExtracted(1L, 2L, extracted, 2L);
        LocalDateTime expired = repo.rows.get(0).getExpiredTime();
        assertTrue(expired != null);

        // 人工编辑不强制 TTL：没填过期时间时保留原值，不擅自把条目改成永久
        MemoryItem manual = new MemoryItem();
        manual.setAgentId(1L);
        manual.setUserId(2L);
        manual.setScope("LONG");
        manual.setFactKey("has_cold");
        manual.setContent("用户这周感冒，多喝水");
        manual.setContentHash(MemoryTexts.hash("用户这周感冒，多喝水"));
        manual.setImportance(60);
        manual.setStatus(1);
        merger.upsertManual(manual, 9L);

        assertEquals(1, repo.rows.size());
        assertEquals(expired, repo.rows.get(0).getExpiredTime());
    }

    /**
     * 所有 LONG 写入都要经过统一 hook：抽取路径应把落库后的条目（含 id）交给双写。
     */
    @Test
    void extractedWriteHooksVectorDualWriteWithPersistedId() {
        merger.upsertExtracted(1L, 2L, draft("residence_city", "城", "住长沙", 50), 2L);

        org.mockito.ArgumentCaptor<MemoryItem> saved = org.mockito.ArgumentCaptor.forClass(MemoryItem.class);
        org.mockito.Mockito.verify(vectors).afterSaved(saved.capture());
        assertEquals(repo.rows.get(0).getId(), saved.getValue().getId());
        assertEquals("LONG", saved.getValue().getScope());
        assertEquals("住长沙", saved.getValue().getContent());
    }

    @Test
    void manualWriteHooksVectorDualWrite() {
        MemoryItem draft = new MemoryItem();
        draft.setAgentId(1L);
        draft.setUserId(2L);
        draft.setScope("LONG");
        draft.setContent("默认上海");
        draft.setContentHash(MemoryTexts.hash("默认上海"));
        draft.setImportance(50);
        draft.setStatus(1);

        merger.upsertManual(draft, 9L);

        org.mockito.Mockito.verify(vectors).afterSaved(org.mockito.ArgumentMatchers.any(MemoryItem.class));
    }

    @Test
    void missingFactKeyNeverHooks() {
        merger.upsertExtracted(1L, 2L, draft(null, "城", "住长沙", 50), 2L);

        org.mockito.Mockito.verifyNoInteractions(vectors);
    }

    /**
     * 偏好族纠正：先入「爱吃评估」，后抽「喜爱食物=苹果」→ 必须只剩一条 canonical，
     * content 以新抽为准覆盖，importance 取 max（只升不降）。
     */
    @Test
    void preferenceFamilyOverwritesInsteadOfAddingSecondRow() {
        MemoryTexts.PreferenceDraft wrong = draft("food_preference", "饮食偏好", "用户爱吃评估", 40);
        MemoryTexts.PreferenceDraft corrected = draft("favorite_food", "喜爱食物", "用户爱吃苹果", 70);

        assertTrue(merger.upsertExtracted(1L, 2L, wrong, 2L).orElseThrow());
        assertEquals(Optional.of(false), merger.upsertExtracted(1L, 2L, corrected, 2L));

        assertEquals(1, repo.rows.size(), "同族只能留一条");
        MemoryItem row = repo.rows.get(0);
        assertEquals(FactKeyNormalizer.FOOD_PREFERENCE, row.getFactKey());
        assertEquals("用户爱吃苹果", row.getContent());
        assertEquals("喜爱食物", row.getTitle());
        assertEquals(70, row.getImportance());
        assertTrue(repo.deletedIds.isEmpty(), "同族单行不需要逻辑删");
    }

    /** 饮品族与饮食族各自独立：喜爱饮品不能并进喜爱食物 */
    @Test
    void beverageFamilyDoesNotMergeIntoFoodFamily() {
        merger.upsertExtracted(1L, 2L, draft("food_preference", "饮食偏好", "用户爱吃苹果", 50), 2L);
        merger.upsertExtracted(1L, 2L, draft("favorite_drink", "喜爱饮品", "用户爱喝美式", 50), 2L);

        assertEquals(2, repo.rows.size());
        assertEquals(FactKeyNormalizer.BEVERAGE_PREFERENCE, repo.rows.get(1).getFactKey());
    }

    /** 无关领域不捏合：健康状态与饮食偏好是两条记忆 */
    @Test
    void unrelatedDomainsAreNotMerged() {
        merger.upsertExtracted(1L, 2L, draft("food_preference", "饮食偏好", "用户爱吃苹果", 50), 2L);
        merger.upsertExtracted(1L, 2L, draft("health_status", "状态", "用户这周感冒", 60), 2L);

        assertEquals(2, repo.rows.size());
    }

    /**
     * 历史遗留行并轨：旧版本没别名时可能已经用 favorite_food / food_preference 各存过一条；
     * 新抽取按 canonical 查整族命中后，留最新一条，其余走逻辑删 + 向量联删。
     */
    @Test
    void legacyAliasRowsCollapseToOneAndRemoveVectors() {
        // 直接把旧行塞进库，模拟历史数据（不经过归一）：两条同族旧值
        repo.insert(legacyRow("favorite_food", "用户爱吃评估", 60), 2L);
        repo.insert(legacyRow("food_preference", "用户爱吃苹果", 50), 2L);

        assertEquals(Optional.of(false), merger.upsertExtracted(1L, 2L,
                draft("favorite_food", "喜爱食物", "用户爱吃苹果", 40), 2L));

        assertEquals(1, repo.rows.size());
        MemoryItem kept = repo.rows.get(0);
        assertEquals(FactKeyNormalizer.FOOD_PREFERENCE, kept.getFactKey());
        assertEquals("用户爱吃苹果", kept.getContent());
        assertEquals(60, kept.getImportance(), "importance 取 max，只升不降");
        assertEquals(1, repo.deletedIds.size(), "多余同族行必须逻辑删，不能留在库里继续被召回");

        org.mockito.ArgumentCaptor<java.util.Collection> removed =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        org.mockito.Mockito.verify(vectors).afterRemoved(removed.capture());
        assertEquals(repo.deletedIds, new ArrayList<>(removed.getValue()));
    }

    /** 幂等：重复抽到同一个正确值不增生行 */
    @Test
    void repeatedSameValueStaysSingleRow() {
        for (int i = 0; i < 3; i++) {
            merger.upsertExtracted(1L, 2L, draft("favorite_food", "喜爱食物", "用户爱吃苹果", 50), 2L);
        }
        assertEquals(1, repo.rows.size());
    }

    private static MemoryItem legacyRow(String factKey, String content, int importance) {
        MemoryItem row = new MemoryItem();
        row.setAgentId(1L);
        row.setUserId(2L);
        row.setScope("LONG");
        row.setFactKey(factKey);
        row.setTitle("旧行");
        row.setContent(content);
        row.setContentHash(MemoryTexts.hash(content));
        row.setImportance(importance);
        row.setStatus(1);
        return row;
    }

    private static final class FakeRepo implements MemoryItemRepository {
        private final List<MemoryItem> rows = new ArrayList<>();
        private final List<Long> deletedIds = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong(1);

        @Override
        public Optional<MemoryItem> findActiveByHash(Long agentId, Long userId, String scope, String hash) {
            return rows.stream()
                    .filter(r -> scope.equals(r.getScope()) && hash.equals(r.getContentHash()))
                    .filter(r -> eq(agentId, r.getAgentId()) && eq(userId, r.getUserId()))
                    .findFirst();
        }

        @Override
        public Optional<MemoryItem> findActiveByFactKey(Long agentId, Long userId, String scope, String factKey) {
            return rows.stream()
                    .filter(r -> scope.equals(r.getScope()) && factKey.equals(r.getFactKey()))
                    .filter(r -> eq(agentId, r.getAgentId()) && eq(userId, r.getUserId()))
                    .findFirst();
        }

        @Override
        public List<MemoryItem> findActiveByFactKeys(Long agentId, Long userId, String scope,
                                                     java.util.Collection<String> factKeys) {
            if (factKeys == null || factKeys.isEmpty()) {
                return List.of();
            }
            // 模拟 DB 的 id 倒序 + 逻辑删过滤，让「留最新一条」的收敛逻辑可被断言
            return rows.stream()
                    .filter(r -> scope.equals(r.getScope()))
                    .filter(r -> r.getFactKey() != null && factKeys.contains(r.getFactKey()))
                    .filter(r -> eq(agentId, r.getAgentId()) && eq(userId, r.getUserId()))
                    .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                    .map(LongMemoryMergerTest::copyOf)
                    .toList();
        }

        @Override
        public Long insert(MemoryItem item, Long operatorId) {
            item.setId(seq.getAndIncrement());
            rows.add(copyOf(item));
            return item.getId();
        }

        @Override
        public void update(MemoryItem item, Long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).getId().equals(item.getId())) {
                    rows.set(i, copyOf(item));
                    return;
                }
            }
        }

        @Override
        public Optional<MemoryItem> findById(Long id) {
            return rows.stream().filter(r -> id.equals(r.getId())).map(LongMemoryMergerTest::copyOf).findFirst();
        }

        @Override
        public PageResult<MemoryItem> page(MemoryQuery query) {
            return PageResult.of(0, 1, 20, List.of());
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
            return rows.stream()
                    .filter(r -> memoryIds != null && memoryIds.contains(r.getId()))
                    .filter(r -> scope.equals(r.getScope()))
                    .filter(r -> r.getStatus() == null || r.getStatus() == 1)
                    .map(LongMemoryMergerTest::copyOf)
                    .toList();
        }

        @Override
        public int countActive(Long agentId, Long userId, String scope) {
            return rows.size();
        }

        @Override
        public List<Long> evictOverflow(Long agentId, Long userId, String scope, int keep, Long operatorId) {
            return List.of();
        }

        @Override
        public void logicalDelete(Long id, Long operatorId) {
            // 记录逻辑删并移出活跃集合，供「同族收敛只留一条」断言
            deletedIds.add(id);
            rows.removeIf(r -> id.equals(r.getId()));
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

        private static boolean eq(Long a, Long b) {
            if (a == null) {
                return b == null;
            }
            return a.equals(b);
        }
    }

    private static MemoryItem copyOf(MemoryItem src) {
        MemoryItem item = new MemoryItem();
        item.setId(src.getId());
        item.setAgentId(src.getAgentId());
        item.setUserId(src.getUserId());
        item.setScope(src.getScope());
        item.setTitle(src.getTitle());
        item.setFactKey(src.getFactKey());
        item.setContent(src.getContent());
        item.setContentHash(src.getContentHash());
        item.setImportance(src.getImportance());
        item.setStatus(src.getStatus());
        item.setRemark(src.getRemark());
        item.setExpiredTime(src.getExpiredTime());
        return item;
    }
}

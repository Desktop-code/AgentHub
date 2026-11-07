package com.agenthub.memory.infra.persistence;

import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryQuery;
import com.agenthub.memory.domain.model.MemoryScope;
import com.agenthub.memory.domain.model.MemoryVectorSyncStatus;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.infra.persistence.entity.MemoryItemEntity;
import com.agenthub.memory.infra.persistence.mapper.MemoryItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 记忆条目仓储实现。所属域：memory。Entity 不出本包。
 */
@Repository
public class MemoryItemRepositoryImpl implements MemoryItemRepository {

    private final MemoryItemMapper mapper;

    /**
     * @param mapper memory_item
     */
    public MemoryItemRepositoryImpl(MemoryItemMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@inheritDoc}
     * lifeStatus=DELETED 走自定义 SQL（绕过 TableLogic），其余走 Wrapper。
     */
    @Override
    public PageResult<MemoryItem> page(MemoryQuery query) {
        long current = PageQuery.clampCurrent(query.getCurrent());
        long size = PageQuery.clampSize(query.getSize());
        String life = query.getLifeStatus() == null ? "" : query.getLifeStatus().trim().toUpperCase();

        // 已删除行被 TableLogic 默认滤掉，必须用显式 deleted=1 的分页 SQL
        if ("DELETED".equals(life)) {
            long total = mapper.countDeleted(query);
            long offset = (current - 1) * size;
            List<MemoryItemEntity> rows = mapper.pageDeleted(query, offset, size);
            return PageResult.of(total, current, size, rows.stream().map(this::toDomain).toList());
        }

        LambdaQueryWrapper<MemoryItemEntity> wrapper = new LambdaQueryWrapper<MemoryItemEntity>()
                .orderByDesc(MemoryItemEntity::getId);
        applyCommonFilters(wrapper, query);
        applyLifeStatus(wrapper, life, query.getStatus());
        IPage<MemoryItemEntity> result = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords().stream().map(this::toDomain).toList());
    }

    /**
     * Agent / scope / session / user / 创建时间：与是否查已删无关的公共条件。
     */
    private void applyCommonFilters(LambdaQueryWrapper<MemoryItemEntity> wrapper, MemoryQuery query) {
        if (query.getAgentId() != null) {
            wrapper.eq(MemoryItemEntity::getAgentId, query.getAgentId());
        }
        if (query.getScope() != null && !query.getScope().isBlank()) {
            wrapper.eq(MemoryItemEntity::getScope, query.getScope().trim());
        }
        if (query.getSessionId() != null) {
            wrapper.eq(MemoryItemEntity::getSessionId, query.getSessionId());
        }
        if (query.getUserId() != null) {
            wrapper.eq(MemoryItemEntity::getUserId, query.getUserId());
        }
        if (query.getCreateTimeFrom() != null) {
            wrapper.ge(MemoryItemEntity::getCreateTime, query.getCreateTimeFrom());
        }
        if (query.getCreateTimeTo() != null) {
            wrapper.le(MemoryItemEntity::getCreateTime, query.getCreateTimeTo());
        }
    }

    /**
     * NORMAL=启用且未过期；EXPIRED=expired_time 已到；空则回退到旧 status 字段。
     */
    private void applyLifeStatus(LambdaQueryWrapper<MemoryItemEntity> wrapper, String life, Integer status) {
        LocalDateTime now = LocalDateTime.now();
        if ("NORMAL".equals(life)) {
            wrapper.eq(MemoryItemEntity::getStatus, 1)
                    .and(one -> one.isNull(MemoryItemEntity::getExpiredTime)
                            .or()
                            .gt(MemoryItemEntity::getExpiredTime, now));
            return;
        }
        if ("EXPIRED".equals(life)) {
            wrapper.isNotNull(MemoryItemEntity::getExpiredTime)
                    .le(MemoryItemEntity::getExpiredTime, now);
            return;
        }
        if (status != null) {
            wrapper.eq(MemoryItemEntity::getStatus, status);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MemoryItem> findById(Long id) {
        MemoryItemEntity entity = mapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MemoryItem> findActiveByHash(Long agentId, Long userId, String scope, String hash) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, LocalDateTime.now())
                .eq(MemoryItemEntity::getContentHash, hash)
                .orderByDesc(MemoryItemEntity::getId)
                .last("LIMIT 1");
        eqOrNull(wrapper, agentId, userId);
        MemoryItemEntity entity = mapper.selectOne(wrapper);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MemoryItem> findActiveByFactKey(Long agentId, Long userId, String scope, String factKey) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, LocalDateTime.now())
                .eq(MemoryItemEntity::getFactKey, factKey)
                .orderByDesc(MemoryItemEntity::getId)
                .last("LIMIT 1");
        eqOrNull(wrapper, agentId, userId);
        MemoryItemEntity entity = mapper.selectOne(wrapper);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryItem> findActiveByFactKeys(Long agentId, Long userId, String scope,
                                                 Collection<String> factKeys) {
        if (factKeys == null || factKeys.isEmpty()) {
            return List.of();
        }
        // 同族键集合天然很小（canonical + 别名），LIMIT 50 只是兜底异常数据，不参与业务判断
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, LocalDateTime.now())
                .in(MemoryItemEntity::getFactKey, factKeys)
                .orderByDesc(MemoryItemEntity::getId)
                .last("LIMIT 50");
        eqOrNull(wrapper, agentId, userId);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryItem> listActiveShort(Long agentId, Long sessionId, LocalDateTime now) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active("SHORT", now)
                .eq(MemoryItemEntity::getAgentId, agentId)
                .eq(MemoryItemEntity::getSessionId, sessionId)
                .orderByAsc(MemoryItemEntity::getId);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryItem> listActive(Long agentId, Long userId, String scope, int limit, LocalDateTime now) {
        return listActive(agentId, userId, scope, limit, 0, now);
    }

    /**
     * {@inheritDoc}
     * <p>
     * importance 下限必须进 SQL 的 WHERE：如果先 LIMIT N 再在内存里过滤，
     * 低 importance 行会先占掉名额，导致合格行不足 N。
     */
    @Override
    public List<MemoryItem> listActive(Long agentId, Long userId, String scope, int limit,
                                       int minImportance, LocalDateTime now) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, now);
        if (minImportance > 0) {
            wrapper.ge(MemoryItemEntity::getImportance, minImportance);
        }
        // 取 Top N 的口径：important 优先，同级看谁更新（update_time 就是「新近程度」）
        wrapper.orderByDesc(MemoryItemEntity::getImportance)
                .orderByDesc(MemoryItemEntity::getUpdateTime)
                .orderByDesc(MemoryItemEntity::getId)
                .last("LIMIT " + Math.max(limit, 1));
        if (agentId != null) {
            wrapper.eq(MemoryItemEntity::getAgentId, agentId);
        }
        if ("LONG".equals(scope)) {
            eqUser(wrapper, userId);
        }
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryItem> listActiveByIds(Collection<Long> memoryIds, String scope, LocalDateTime now) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, now)
                .in(MemoryItemEntity::getId, memoryIds);
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public int countActive(Long agentId, Long userId, String scope) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(scope, LocalDateTime.now());
        if (agentId != null) {
            wrapper.eq(MemoryItemEntity::getAgentId, agentId);
        }
        if ("LONG".equals(scope)) {
            eqUser(wrapper, userId);
        }
        Long count = mapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>刻意不加「未过期」条件</b>：过期行同样占 {@code max_long_items} 名额，
     * 若只按有效行淘汰，过期条会一直堆着把有效条挤出上限。
     * 淘汰顺序：已过期 → importance 低 → 更旧（update_time 早、id 小）。
     * 未超限时什么都不删，过期行的清理仍走 {@link #purgeExpired}。
     */
    @Override
    public List<Long> evictOverflow(Long agentId, Long userId, String scope, int keep, Long operatorId) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = new LambdaQueryWrapper<MemoryItemEntity>()
                .eq(MemoryItemEntity::getScope, scope);
        if (agentId != null) {
            wrapper.eq(MemoryItemEntity::getAgentId, agentId);
        }
        if ("LONG".equals(scope)) {
            eqUser(wrapper, userId);
        }
        List<MemoryItemEntity> rows = mapper.selectList(wrapper);
        int retain = Math.max(keep, 0);
        int drop = rows.size() - retain;
        if (drop <= 0) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        rows.sort(Comparator
                .comparingInt((MemoryItemEntity row) -> isExpired(row, now) ? 0 : 1)
                .thenComparingInt(row -> row.getImportance() == null ? 0 : row.getImportance())
                .thenComparing(row -> row.getUpdateTime() == null ? LocalDateTime.MIN : row.getUpdateTime())
                .thenComparing(row -> row.getId() == null ? 0L : row.getId()));
        List<Long> evicted = new ArrayList<>(drop);
        for (int i = 0; i < drop; i++) {
            Long id = rows.get(i).getId();
            if (id == null) {
                continue;
            }
            mapper.deleteById(id);
            evicted.add(id);
        }
        return evicted;
    }

    /**
     * @return 是否已过期（expired_time 非空且不晚于当前）
     */
    private static boolean isExpired(MemoryItemEntity row, LocalDateTime now) {
        return row.getExpiredTime() != null && !row.getExpiredTime().isAfter(now);
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(MemoryItem item, Long operatorId) {
        MemoryItemEntity entity = new MemoryItemEntity();
        copy(item, entity);
        // 状态位只对 LONG 有意义：向量同步范围硬锁 scope=LONG。
        // LONG 从 PENDING 起步（写向量是提交后异步的）；SHORT / AGENT 永远不该有记忆向量，
        // 直接落终态 SYNCED，避免它们停在 PENDING 制造「未同步」的假象。
        boolean longScope = MemoryScope.LONG.name().equals(item.getScope());
        entity.setVectorSyncStatus(longScope
                ? MemoryVectorSyncStatus.PENDING.name()
                : MemoryVectorSyncStatus.SYNCED.name());
        entity.setVectorSyncFailCount(0);
        entity.setVectorSyncError(null);
        entity.setCreatedBy(operatorId);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdatedBy(operatorId);
        entity.setUpdateTime(entity.getCreateTime());
        entity.setDeleted(0);
        mapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(MemoryItem item, Long operatorId) {
        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setId(item.getId());
        copy(item, entity);
        entity.setUpdatedBy(operatorId);
        entity.setUpdateTime(LocalDateTime.now());
        mapper.updateById(entity);
    }

    /** {@inheritDoc} */
    @Override
    public void logicalDelete(Long id, Long operatorId) {
        mapper.deleteById(id);
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> purgeExpired(LocalDateTime now, int limit) {
        int cap = Math.max(limit, 1);
        // 先取主键（含 scope）再删：逻辑删除不返回 id，而向量联动删除需要这些 id
        List<MemoryItemEntity> rows = mapper.selectList(expiredWrapper(now)
                .select(MemoryItemEntity::getId, MemoryItemEntity::getScope)
                .orderByAsc(MemoryItemEntity::getId)
                .last("LIMIT " + cap));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> allIds = rows.stream()
                .map(MemoryItemEntity::getId)
                .filter(id -> id != null)
                .toList();
        if (allIds.isEmpty()) {
            return List.of();
        }
        // 按取到的主键删：与查询同批，避免两次条件求值之间的时间漂移导致多删
        mapper.deleteBatchIds(allIds);
        // 只把 LONG 的 id 往上抛：SHORT / AGENT 在向量库里没有点，抛上去只是白出站
        return rows.stream()
                .filter(row -> MemoryScope.LONG.name().equals(row.getScope()))
                .map(MemoryItemEntity::getId)
                .filter(id -> id != null)
                .toList();
    }

    /**
     * 已过期条件：expired_time 非空且早于 now。选择与删除共用同一口径。
     */
    private static LambdaQueryWrapper<MemoryItemEntity> expiredWrapper(LocalDateTime now) {
        return new LambdaQueryWrapper<MemoryItemEntity>()
                .isNotNull(MemoryItemEntity::getExpiredTime)
                .lt(MemoryItemEntity::getExpiredTime, now);
    }

    /** {@inheritDoc} */
    @Override
    public int markVectorSync(Collection<Long> memoryIds, MemoryVectorSyncStatus status, int failCount, String error) {
        if (memoryIds == null || memoryIds.isEmpty() || status == null) {
            return 0;
        }
        List<Long> ids = memoryIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        // 走自定义 SQL：逻辑删除后的行也要能落终态，MyBatis-Plus 的 updateById 会被 TableLogic 挡掉；
        // scope='LONG' 由 SQL 硬锁，非 LONG 行即使传进来也不会被改
        return mapper.updateVectorSync(ids, status.name(), Math.max(failCount, 0), error);
    }

    /** {@inheritDoc} */
    @Override
    public int markVectorSyncStatus(Collection<Long> memoryIds, MemoryVectorSyncStatus status) {
        List<Long> ids = positive(memoryIds);
        if (ids.isEmpty() || status == null) {
            return 0;
        }
        return mapper.updateVectorSyncStatus(ids, status.name());
    }

    /** {@inheritDoc} */
    @Override
    public int bumpVectorSyncFail(Collection<Long> memoryIds, MemoryVectorSyncStatus status, String error) {
        List<Long> ids = positive(memoryIds);
        if (ids.isEmpty() || status == null) {
            return 0;
        }
        // 自增在 SQL 里做：联删入口没有行上的历史次数，传绝对值会把计数压回 1
        return mapper.bumpVectorSyncFail(ids, status.name(), error);
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> listVectorDeleteCandidates(LocalDateTime now, int limit, int maxFailCount) {
        return mapper.listVectorDeleteCandidates(now, Math.max(limit, 1), Math.max(maxFailCount, 1));
    }

    /**
     * 去重并剔除非正主键，避免把 null / 0 写进 IN 条件。
     */
    private static List<Long> positive(Collection<Long> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return List.of();
        }
        return memoryIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<MemoryItem> listVectorSyncCandidates(Long agentId, int limit, int maxFailCount,
                                                     LocalDateTime now) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(MemoryScope.LONG.name(), now)
                .in(MemoryItemEntity::getVectorSyncStatus,
                        MemoryVectorSyncStatus.PENDING.name(), MemoryVectorSyncStatus.FAILED.name())
                // 到上限的行停在 FAILED 只打日志：这里直接排除，避免每轮空转
                .lt(MemoryItemEntity::getVectorSyncFailCount, Math.max(maxFailCount, 1))
                .orderByAsc(MemoryItemEntity::getId)
                .last("LIMIT " + Math.max(limit, 1));
        if (agentId != null) {
            wrapper.eq(MemoryItemEntity::getAgentId, agentId);
        }
        return mapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> listLongAgentIds() {
        return mapper.listLongAgentIds();
    }

    /** {@inheritDoc} */
    @Override
    public int countVectorSyncCandidates(int maxFailCount, LocalDateTime now) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = active(MemoryScope.LONG.name(), now)
                .in(MemoryItemEntity::getVectorSyncStatus,
                        MemoryVectorSyncStatus.PENDING.name(), MemoryVectorSyncStatus.FAILED.name())
                .lt(MemoryItemEntity::getVectorSyncFailCount, Math.max(maxFailCount, 1));
        Long count = mapper.selectCount(wrapper);
        return count == null ? 0 : count.intValue();
    }

    /** {@inheritDoc} */
    @Override
    public int requeueValidLong(LocalDateTime now) {
        // 走 XML：批量 UPDATE 一次收口，避免逐行 updateById 把几十万行拆成几十万次往返
        return mapper.requeueValidLong(now);
    }

    private LambdaQueryWrapper<MemoryItemEntity> active(String scope, LocalDateTime now) {
        return new LambdaQueryWrapper<MemoryItemEntity>()
                .eq(MemoryItemEntity::getScope, scope)
                .eq(MemoryItemEntity::getStatus, 1)
                .and(one -> one.isNull(MemoryItemEntity::getExpiredTime)
                        .or()
                        .gt(MemoryItemEntity::getExpiredTime, now));
    }

    private void eqOrNull(LambdaQueryWrapper<MemoryItemEntity> wrapper, Long agentId, Long userId) {
        if (agentId == null) {
            wrapper.isNull(MemoryItemEntity::getAgentId);
        } else {
            wrapper.eq(MemoryItemEntity::getAgentId, agentId);
        }
        eqUser(wrapper, userId);
    }

    private void eqUser(LambdaQueryWrapper<MemoryItemEntity> wrapper, Long userId) {
        if (userId == null) {
            wrapper.isNull(MemoryItemEntity::getUserId);
        } else {
            wrapper.eq(MemoryItemEntity::getUserId, userId);
        }
    }

    private void copy(MemoryItem item, MemoryItemEntity entity) {
        entity.setAgentId(item.getAgentId());
        entity.setUserId(item.getUserId());
        entity.setAppId(item.getAppId());
        entity.setSessionId(item.getSessionId());
        entity.setScope(item.getScope());
        entity.setTitle(item.getTitle());
        entity.setFactKey(item.getFactKey());
        entity.setContent(item.getContent());
        entity.setContentHash(item.getContentHash());
        entity.setImportance(item.getImportance() == null ? 0 : item.getImportance());
        entity.setStatus(item.getStatus() == null ? 1 : item.getStatus());
        entity.setExpiredTime(item.getExpiredTime());
        entity.setRemark(item.getRemark());
        // 状态位只在显式指定时跟随（null 表示「这次不碰」，避免把异步写入的结论覆盖掉）
        entity.setVectorSyncStatus(item.getVectorSyncStatus());
        entity.setVectorSyncFailCount(item.getVectorSyncFailCount());
        entity.setVectorSyncError(item.getVectorSyncError());
    }

    private MemoryItem toDomain(MemoryItemEntity entity) {
        MemoryItem item = new MemoryItem();
        item.setId(entity.getId());
        item.setAgentId(entity.getAgentId());
        item.setUserId(entity.getUserId());
        item.setAppId(entity.getAppId());
        item.setSessionId(entity.getSessionId());
        item.setScope(entity.getScope());
        item.setTitle(entity.getTitle());
        item.setFactKey(entity.getFactKey());
        item.setContent(entity.getContent());
        item.setContentHash(entity.getContentHash());
        item.setImportance(entity.getImportance());
        item.setStatus(entity.getStatus());
        item.setExpiredTime(entity.getExpiredTime());
        item.setRemark(entity.getRemark());
        item.setVectorSyncStatus(entity.getVectorSyncStatus());
        item.setVectorSyncFailCount(entity.getVectorSyncFailCount());
        item.setVectorSyncError(entity.getVectorSyncError());
        item.setCreatedBy(entity.getCreatedBy());
        item.setCreateTime(entity.getCreateTime());
        item.setUpdatedBy(entity.getUpdatedBy());
        item.setUpdateTime(entity.getUpdateTime());
        return item;
    }
}

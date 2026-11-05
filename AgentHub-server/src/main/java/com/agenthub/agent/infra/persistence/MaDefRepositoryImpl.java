package com.agenthub.agent.infra.persistence;

import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.model.MaDefQuery;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.infra.persistence.entity.MaCandidateEntity;
import com.agenthub.agent.infra.persistence.entity.MaDefEntity;
import com.agenthub.agent.infra.persistence.mapper.MaCandidateMapper;
import com.agenthub.agent.infra.persistence.mapper.MaDefMapper;
import com.agenthub.common.api.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多智能体方案仓储实现。所属域：agent 的 infra。Entity 不出本包。
 *
 * <p>租户过滤：列表强制带 tenant_id；按 id 查询故意不带（应用层区分 404 与越权）；
 * 所有写操作额外带 tenant_id，避免应用层判断漏掉时被越权改。
 */
@Repository
public class MaDefRepositoryImpl implements MaDefRepository {

    private final MaDefMapper defMapper;
    private final MaCandidateMapper candidateMapper;

    /**
     * @param defMapper       ma_def
     * @param candidateMapper ma_candidate
     */
    public MaDefRepositoryImpl(MaDefMapper defMapper, MaCandidateMapper candidateMapper) {
        this.defMapper = defMapper;
        this.candidateMapper = candidateMapper;
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<MaDef> page(MaDefQuery query) {
        long current = Math.max(query.getCurrent(), 1);
        long size = Math.min(Math.max(query.getSize(), 1), 100);
        LambdaQueryWrapper<MaDefEntity> wrapper = new LambdaQueryWrapper<MaDefEntity>()
                .eq(MaDefEntity::getTenantId, query.getTenantId())
                .orderByDesc(MaDefEntity::getUpdateTime)
                .orderByDesc(MaDefEntity::getId);
        String keyword = likeText(query.getKeyword());
        if (keyword != null) {
            // 关键词同时扫 code 与中文名，方便运营检索
            wrapper.and(one -> one.like(MaDefEntity::getCode, keyword)
                    .or()
                    .like(MaDefEntity::getName, keyword));
        }
        if (query.getEnabled() != null) {
            wrapper.eq(MaDefEntity::getEnabled, query.getEnabled());
        }
        if (query.getTemplate() != null && !query.getTemplate().isBlank()) {
            wrapper.eq(MaDefEntity::getTemplate, query.getTemplate().trim());
        }
        if (query.getHasRoot() != null) {
            // true=有绑根；false=虚拟根。空根方案也要能筛出来，不能按值相等误判
            if (Boolean.TRUE.equals(query.getHasRoot())) {
                wrapper.isNotNull(MaDefEntity::getRootAgentId);
            } else {
                wrapper.isNull(MaDefEntity::getRootAgentId);
            }
        }
        IPage<MaDefEntity> page = defMapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(this::toDomain).toList());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MaDef> findById(Long id) {
        MaDefEntity entity = defMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MaDef> findByTenantAndCode(long tenantId, String code) {
        MaDefEntity entity = defMapper.selectOne(new LambdaQueryWrapper<MaDefEntity>()
                .eq(MaDefEntity::getTenantId, tenantId)
                .eq(MaDefEntity::getCode, code));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MaDef> findEnabledByRoot(long tenantId, long rootAgentId, Long excludeId) {
        LambdaQueryWrapper<MaDefEntity> wrapper = new LambdaQueryWrapper<MaDefEntity>()
                .eq(MaDefEntity::getTenantId, tenantId)
                .eq(MaDefEntity::getRootAgentId, rootAgentId)
                .eq(MaDefEntity::getEnabled, 1);
        if (excludeId != null) {
            wrapper.ne(MaDefEntity::getId, excludeId);
        }
        MaDefEntity entity = defMapper.selectOne(wrapper.last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MaDef> findEnabledByRoot(long tenantId, long rootAgentId) {
        return findEnabledByRoot(tenantId, rootAgentId, null);
    }

    /** {@inheritDoc} */
    @Override
    public List<MaCandidate> listCandidates(Long maId) {
        return candidateMapper.selectList(new LambdaQueryWrapper<MaCandidateEntity>()
                        .eq(MaCandidateEntity::getMaId, maId)
                        .orderByAsc(MaCandidateEntity::getSortNo)
                        .orderByAsc(MaCandidateEntity::getId))
                .stream()
                .map(this::toCandidate)
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public Map<Long, Integer> countEnabledCandidates(Collection<Long> maIds) {
        if (maIds == null || maIds.isEmpty()) {
            return Map.of();
        }
        // 一条 group by 拿整页候选数，避免列表逐行 count 的 N+1
        List<Map<String, Object>> grouped = candidateMapper.selectMaps(new QueryWrapper<MaCandidateEntity>()
                .select("ma_id", "COUNT(*) AS cnt")
                .eq("enabled", 1)
                .in("ma_id", maIds)
                .groupBy("ma_id"));
        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : grouped) {
            Object idValue = row.get("ma_id");
            Object countValue = row.get("cnt");
            if (idValue instanceof Number id && countValue instanceof Number count) {
                counts.put(id.longValue(), count.intValue());
            }
        }
        return counts;
    }

    /** {@inheritDoc} */
    @Override
    public Long insert(MaDef def, Long operatorId) {
        MaDefEntity entity = new MaDefEntity();
        entity.setTenantId(def.getTenantId());
        entity.setCode(def.getCode());
        entity.setName(def.getName());
        entity.setTemplate(def.getTemplate());
        entity.setConfigJson(def.getConfigJson());
        entity.setRootAgentId(def.getRootAgentId());
        entity.setEnabled(def.getEnabled());
        entity.setVersion(def.getVersion() == null ? 1 : def.getVersion());
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        defMapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public void update(MaDef def, Long operatorId) {
        defMapper.update(null, new LambdaUpdateWrapper<MaDefEntity>()
                .eq(MaDefEntity::getId, def.getId())
                .eq(MaDefEntity::getTenantId, def.getTenantId())
                .set(MaDefEntity::getName, def.getName())
                .set(MaDefEntity::getCode, def.getCode())
                .set(MaDefEntity::getTemplate, def.getTemplate())
                .set(MaDefEntity::getConfigJson, def.getConfigJson())
                .set(MaDefEntity::getRootAgentId, def.getRootAgentId())
                .set(MaDefEntity::getEnabled, def.getEnabled())
                .set(MaDefEntity::getUpdatedBy, operatorId)
                // version 自增交给数据库，避免「先读再写」在并发保存时丢计数
                .setSql("version = version + 1"));
    }

    /** {@inheritDoc} */
    @Override
    public int updateEnabled(Long id, long tenantId, int enabled, Long operatorId) {
        return defMapper.update(null, new LambdaUpdateWrapper<MaDefEntity>()
                .eq(MaDefEntity::getId, id)
                .eq(MaDefEntity::getTenantId, tenantId)
                .set(MaDefEntity::getEnabled, enabled)
                .set(MaDefEntity::getUpdatedBy, operatorId));
    }

    /** {@inheritDoc} */
    @Override
    public int delete(Long id, long tenantId) {
        // 先按 tenant 删除方案行；只有确实删到本租户的行，才清理候选，避免跨租户误删
        int affected = defMapper.delete(new LambdaQueryWrapper<MaDefEntity>()
                .eq(MaDefEntity::getId, id)
                .eq(MaDefEntity::getTenantId, tenantId));
        if (affected > 0) {
            // 候选是方案从属子资源，同事务清理，避免残留孤儿候选
            deleteCandidates(id);
        }
        return affected;
    }

    /** {@inheritDoc} */
    @Override
    public void replaceCandidates(Long maId, List<MaCandidate> candidates) {
        deleteCandidates(maId);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        for (MaCandidate candidate : candidates) {
            MaCandidateEntity entity = new MaCandidateEntity();
            entity.setMaId(maId);
            entity.setAgentId(candidate.getAgentId());
            entity.setRouteKey(candidate.getRouteKey());
            entity.setIntentDesc(candidate.getIntentDesc());
            entity.setSortNo(candidate.getSortNo());
            entity.setEnabled(candidate.getEnabled());
            candidateMapper.insert(entity);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteCandidates(Long maId) {
        candidateMapper.delete(new LambdaQueryWrapper<MaCandidateEntity>()
                .eq(MaCandidateEntity::getMaId, maId));
    }

    /**
     * MySQL 默认反斜杠转义 LIKE；这里抹掉用户输入里的通配符含义。
     */
    private String likeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private MaDef toDomain(MaDefEntity entity) {
        MaDef def = new MaDef();
        def.setId(entity.getId());
        def.setTenantId(entity.getTenantId());
        def.setCode(entity.getCode());
        def.setName(entity.getName());
        def.setTemplate(entity.getTemplate());
        def.setConfigJson(entity.getConfigJson());
        def.setRootAgentId(entity.getRootAgentId());
        def.setEnabled(entity.getEnabled());
        def.setVersion(entity.getVersion());
        def.setCreatedBy(entity.getCreatedBy());
        def.setCreateTime(entity.getCreateTime());
        def.setUpdatedBy(entity.getUpdatedBy());
        def.setUpdateTime(entity.getUpdateTime());
        return def;
    }

    private MaCandidate toCandidate(MaCandidateEntity entity) {
        MaCandidate candidate = new MaCandidate();
        candidate.setId(entity.getId());
        candidate.setMaId(entity.getMaId());
        candidate.setAgentId(entity.getAgentId());
        candidate.setRouteKey(entity.getRouteKey());
        candidate.setIntentDesc(entity.getIntentDesc());
        candidate.setSortNo(entity.getSortNo());
        candidate.setEnabled(entity.getEnabled());
        candidate.setCreateTime(entity.getCreateTime());
        candidate.setUpdateTime(entity.getUpdateTime());
        return candidate;
    }
}

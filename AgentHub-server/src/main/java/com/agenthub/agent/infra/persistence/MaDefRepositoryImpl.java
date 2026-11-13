package com.agenthub.agent.infra.persistence;

import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.model.MaDefQuery;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.infra.persistence.entity.MaCandidateEntity;
import com.agenthub.agent.infra.persistence.entity.MaDefEntity;
import com.agenthub.agent.infra.persistence.mapper.MaCandidateMapper;
import com.agenthub.agent.infra.persistence.mapper.MaDefMapper;
import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.text.LikeText;
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
import java.util.Objects;
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
        long current = PageQuery.clampCurrent(query.getCurrent());
        long size = PageQuery.clampSize(query.getSize());
        LambdaQueryWrapper<MaDefEntity> wrapper = new LambdaQueryWrapper<MaDefEntity>()
                .eq(MaDefEntity::getTenantId, query.getTenantId())
                .orderByDesc(MaDefEntity::getUpdateTime)
                .orderByDesc(MaDefEntity::getId);
        String keyword = LikeText.contains(query.getKeyword());
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
            // 按新模式筛选时把尚未重存的历史模板一起带上，避免「路由后串行」筛不到旧的固定串行
            List<String> templates = AgentRuntimeConfig.storedTemplatesForFilter(query.getTemplate());
            if (templates.size() == 1) {
                wrapper.eq(MaDefEntity::getTemplate, templates.get(0));
            } else if (!templates.isEmpty()) {
                wrapper.in(MaDefEntity::getTemplate, templates);
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
    public List<MaDef> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return defMapper.selectBatchIds(ids).stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public List<MaDef> listByTenant(long tenantId) {
        // 候选池下拉用：按更新时间倒序，运营刚调过的方案排在前面
        return defMapper.selectList(new LambdaQueryWrapper<MaDefEntity>()
                        .eq(MaDefEntity::getTenantId, tenantId)
                        .orderByDesc(MaDefEntity::getUpdateTime)
                        .orderByDesc(MaDefEntity::getId)).stream()
                .map(this::toDomain)
                .toList();
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
        entity.setDescription(def.getDescription());
        entity.setAvatarConfig(def.getAvatarConfigJson());
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
                .set(MaDefEntity::getDescription, def.getDescription())
                .set(MaDefEntity::getAvatarConfig, def.getAvatarConfigJson())
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
    public Optional<MaDef> findReceptionDefault(long tenantId) {
        // 故意不带 enabled：调用方要能区分「没配默认」与「默认已停用」，并给出不同文案
        MaDefEntity entity = defMapper.selectOne(new LambdaQueryWrapper<MaDefEntity>()
                .eq(MaDefEntity::getTenantId, tenantId)
                .eq(MaDefEntity::getIsReceptionDefault, 1)
                .last("LIMIT 1"));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> listCandidateAgentIds(long tenantId) {
        // ma_candidate 无 tenant_id：先按方案 id 限定本租户，避免别的租户的候选把下拉挤空
        List<MaDefEntity> defs = defMapper.selectList(new LambdaQueryWrapper<MaDefEntity>()
                .select(MaDefEntity::getId)
                .eq(MaDefEntity::getTenantId, tenantId));
        if (defs.isEmpty()) {
            return List.of();
        }
        List<Long> maIds = defs.stream().map(MaDefEntity::getId).toList();
        return candidateMapper.selectList(new LambdaQueryWrapper<MaCandidateEntity>()
                        .select(MaCandidateEntity::getAgentId)
                        .in(MaCandidateEntity::getMaId, maIds))
                .stream()
                // 刀5 起 MA 候选的 agent_id 为 NULL：它不占用任何子 Agent，不能进「已占用」集合
                .map(MaCandidateEntity::getAgentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public int updateReceptionDefault(Long id, long tenantId, boolean on, Long operatorId) {
        if (on) {
            // 先清本租户其它默认位：唯一索引只允许一条，先清后设才能在同一事务里换默认
            defMapper.update(null, new LambdaUpdateWrapper<MaDefEntity>()
                    .eq(MaDefEntity::getTenantId, tenantId)
                    .eq(MaDefEntity::getIsReceptionDefault, 1)
                    .ne(MaDefEntity::getId, id)
                    .set(MaDefEntity::getIsReceptionDefault, 0)
                    .set(MaDefEntity::getUpdatedBy, operatorId));
        }
        return defMapper.update(null, new LambdaUpdateWrapper<MaDefEntity>()
                .eq(MaDefEntity::getId, id)
                .eq(MaDefEntity::getTenantId, tenantId)
                .set(MaDefEntity::getIsReceptionDefault, on ? 1 : 0)
                .set(MaDefEntity::getUpdatedBy, operatorId));
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
            entity.setTargetType(targetTypeOf(candidate));
            entity.setAgentId(candidate.getAgentId());
            entity.setMaDefId(candidate.getMaDefId());
            entity.setWorkflowDefId(candidate.getWorkflowDefId());
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

    /** {@inheritDoc} */
    @Override
    public Long insertCandidate(MaCandidate candidate) {
        MaCandidateEntity entity = new MaCandidateEntity();
        entity.setMaId(candidate.getMaId());
        entity.setTargetType(targetTypeOf(candidate));
        entity.setAgentId(candidate.getAgentId());
        entity.setMaDefId(candidate.getMaDefId());
        entity.setWorkflowDefId(candidate.getWorkflowDefId());
        entity.setRouteKey(candidate.getRouteKey());
        entity.setIntentDesc(candidate.getIntentDesc());
        entity.setSortNo(candidate.getSortNo());
        entity.setEnabled(candidate.getEnabled());
        candidateMapper.insert(entity);
        return entity.getId();
    }

    /** {@inheritDoc} */
    @Override
    public int updateCandidate(Long id, Long maId, String intentDesc, Integer sortNo, Integer enabled) {
        LambdaUpdateWrapper<MaCandidateEntity> wrapper = new LambdaUpdateWrapper<MaCandidateEntity>()
                .eq(MaCandidateEntity::getId, id)
                .eq(MaCandidateEntity::getMaId, maId);
        // 只 set 传进来的列：简易配置页的「排序」「改意图」各走各的，不要互相覆盖
        if (intentDesc != null) {
            wrapper.set(MaCandidateEntity::getIntentDesc, intentDesc);
        }
        if (sortNo != null) {
            wrapper.set(MaCandidateEntity::getSortNo, sortNo);
        }
        if (enabled != null) {
            wrapper.set(MaCandidateEntity::getEnabled, enabled);
        }
        return candidateMapper.update(null, wrapper);
    }

    /** {@inheritDoc} */
    @Override
    public int deleteCandidate(Long id, Long maId) {
        return candidateMapper.delete(new LambdaQueryWrapper<MaCandidateEntity>()
                .eq(MaCandidateEntity::getId, id)
                .eq(MaCandidateEntity::getMaId, maId));
    }


    /**
     * 候选目标类型落库值：空值一律当 AGENT（与列默认值一致），避免脏数据让 MA 判定失真。
     *
     * @param candidate 候选
     * @return {@link MaCandidate#TARGET_AGENT} 或 {@link MaCandidate#TARGET_MA}
     */
    private static String targetTypeOf(MaCandidate candidate) {
        return candidate.targetTypeOrDefault();
    }

    private MaDef toDomain(MaDefEntity entity) {
        MaDef def = new MaDef();
        def.setId(entity.getId());
        def.setTenantId(entity.getTenantId());
        def.setCode(entity.getCode());
        def.setName(entity.getName());
        def.setDescription(entity.getDescription());
        def.setAvatarConfigJson(entity.getAvatarConfig());
        def.setTemplate(entity.getTemplate());
        def.setConfigJson(entity.getConfigJson());
        def.setRootAgentId(entity.getRootAgentId());
        def.setEnabled(entity.getEnabled());
        def.setIsReceptionDefault(entity.getIsReceptionDefault());
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
        candidate.setTargetType(entity.getTargetType());
        candidate.setAgentId(entity.getAgentId());
        candidate.setMaDefId(entity.getMaDefId());
        candidate.setWorkflowDefId(entity.getWorkflowDefId());
        candidate.setRouteKey(entity.getRouteKey());
        candidate.setIntentDesc(entity.getIntentDesc());
        candidate.setSortNo(entity.getSortNo());
        candidate.setEnabled(entity.getEnabled());
        candidate.setCreateTime(entity.getCreateTime());
        candidate.setUpdateTime(entity.getUpdateTime());
        return candidate;
    }
}

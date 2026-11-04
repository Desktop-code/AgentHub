package com.agenthub.eval.infra.persistence;

import com.agenthub.eval.domain.model.EvalCase;
import com.agenthub.eval.domain.repository.EvalCaseRepository;
import com.agenthub.eval.infra.persistence.entity.EvalCaseEntity;
import com.agenthub.eval.infra.persistence.mapper.EvalCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 评测用例仓储实现。所属域：eval 的 infra。Entity 不出本包。
 * <p>版本语义：只读历史版本 + 追加新版本行；本类**没有**按 id 改内容的入口，
 * 从结构上杜绝「静默改历史版本」。套件级租户校验由应用层完成（用例表本身无 tenant_id）。
 */
@Repository
public class EvalCaseRepositoryImpl implements EvalCaseRepository {

    private final EvalCaseMapper mapper;

    /**
     * @param mapper eval_case
     */
    public EvalCaseRepositoryImpl(EvalCaseMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public List<EvalCase> listByVersion(Long suiteId, int suiteVersion) {
        List<EvalCaseEntity> rows = mapper.selectList(new LambdaQueryWrapper<EvalCaseEntity>()
                .eq(EvalCaseEntity::getSuiteId, suiteId)
                .eq(EvalCaseEntity::getSuiteVersion, suiteVersion)
                .orderByAsc(EvalCaseEntity::getSortOrder)
                .orderByAsc(EvalCaseEntity::getId));
        return rows.stream().map(this::toDomain).toList();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EvalCase> findByKey(Long suiteId, int suiteVersion, String caseKey) {
        EvalCaseEntity entity = mapper.selectOne(new LambdaQueryWrapper<EvalCaseEntity>()
                .eq(EvalCaseEntity::getSuiteId, suiteId)
                .eq(EvalCaseEntity::getSuiteVersion, suiteVersion)
                .eq(EvalCaseEntity::getCaseKey, caseKey));
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    /**
     * {@inheritDoc}
     * 逐条 insert 而不用批量 SQL：单套件上限 500 条（EvalSpecs.CASES_MAX），
     * 量级小，换取「失败即整事务回滚」的简单语义，也避免 MySQL 5.7 批量语句拼接踩坑。
     */
    @Override
    public int insertBatch(List<EvalCase> cases, Long operatorId) {
        int written = 0;
        for (EvalCase one : cases) {
            EvalCaseEntity entity = new EvalCaseEntity();
            entity.setSuiteId(one.getSuiteId());
            entity.setSuiteVersion(one.getSuiteVersion());
            entity.setCaseKey(one.getCaseKey());
            entity.setTitle(one.getTitle());
            entity.setTagsJson(one.getTagsJson());
            entity.setInputJson(one.getInputJson());
            entity.setExpectJson(one.getExpectJson());
            entity.setAssertionsJson(one.getAssertionsJson());
            entity.setScoringJson(one.getScoringJson());
            entity.setEnabled(one.getEnabled());
            entity.setSortOrder(one.getSortOrder());
            entity.setCreateBy(operatorId);
            entity.setUpdateBy(operatorId);
            mapper.insert(entity);
            written++;
        }
        return written;
    }

    /** {@inheritDoc} */
    @Override
    public long countByVersion(Long suiteId, int suiteVersion) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<EvalCaseEntity>()
                .eq(EvalCaseEntity::getSuiteId, suiteId)
                .eq(EvalCaseEntity::getSuiteVersion, suiteVersion));
        return count == null ? 0L : count;
    }

    /**
     * {@inheritDoc}
     * 按套件墓碑化所有版本：套件删除后历史版本不再参与任何查询，
     * 但行仍在库里，便于事后排查「当时跑的是哪版用例」。
     */
    @Override
    public int logicalDeleteBySuite(Long suiteId, Long operatorId) {
        int touched = mapper.update(null, new LambdaUpdateWrapper<EvalCaseEntity>()
                .eq(EvalCaseEntity::getSuiteId, suiteId)
                .set(EvalCaseEntity::getUpdateBy, operatorId));
        mapper.delete(new LambdaQueryWrapper<EvalCaseEntity>().eq(EvalCaseEntity::getSuiteId, suiteId));
        return touched;
    }

    private EvalCase toDomain(EvalCaseEntity entity) {
        EvalCase one = new EvalCase();
        one.setId(entity.getId());
        one.setSuiteId(entity.getSuiteId());
        one.setSuiteVersion(entity.getSuiteVersion());
        one.setCaseKey(entity.getCaseKey());
        one.setTitle(entity.getTitle());
        one.setTagsJson(entity.getTagsJson());
        one.setInputJson(entity.getInputJson());
        one.setExpectJson(entity.getExpectJson());
        one.setAssertionsJson(entity.getAssertionsJson());
        one.setScoringJson(entity.getScoringJson());
        one.setEnabled(entity.getEnabled());
        one.setSortOrder(entity.getSortOrder());
        one.setCreatedBy(entity.getCreateBy());
        one.setCreateTime(entity.getCreateTime());
        one.setUpdatedBy(entity.getUpdateBy());
        one.setUpdateTime(entity.getUpdateTime());
        return one;
    }
}

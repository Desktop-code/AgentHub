package com.agenthub.eval.infra.persistence.mapper;

import com.agenthub.eval.infra.persistence.entity.EvalSuiteEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * eval_suite 映射。所属域：eval 的 infra。
 */
@Mapper
public interface EvalSuiteMapper extends BaseMapper<EvalSuiteEntity> {

    /**
     * 统计键位占用（**含已逻辑删的行**）。
     * <p>为什么不用 selectCount：@TableLogic 会自动追加 deleted=0，
     * 而物理 UK (tenant_id, suite_key) 对软删行依然生效，必须看全量才能给出可读的冲突提示。
     *
     * @param tenantId 租户
     * @param suiteKey 逻辑键
     * @return 占用行数
     */
    @Select("SELECT COUNT(*) FROM eval_suite WHERE tenant_id = #{tenantId} AND suite_key = #{suiteKey}")
    long countByKeyIncludingDeleted(@Param("tenantId") long tenantId, @Param("suiteKey") String suiteKey);
}

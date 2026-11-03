package com.agenthub.eval.infra.persistence.mapper;

import com.agenthub.eval.infra.persistence.entity.EvalResultEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * eval_result 映射。所属域：eval 的 infra。
 * <p>「把未出判定的行打成 SKIPPED」用 LambdaUpdateWrapper 的 IS NULL 条件即可，
 * 不需要自定义 XML（保持刀1 的「无 XML」风格）。
 */
@Mapper
public interface EvalResultMapper extends BaseMapper<EvalResultEntity> {
}

package com.agenthub.eval.infra.persistence.mapper;

import com.agenthub.eval.infra.persistence.entity.EvalCaseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * eval_case 映射。所属域：eval 的 infra。
 */
@Mapper
public interface EvalCaseMapper extends BaseMapper<EvalCaseEntity> {
}

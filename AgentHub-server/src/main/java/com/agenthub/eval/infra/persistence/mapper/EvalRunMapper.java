package com.agenthub.eval.infra.persistence.mapper;

import com.agenthub.eval.infra.persistence.entity.EvalRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * eval_run 映射。所属域：eval 的 infra。
 * <p>本刀的条件查询都在仓储里用 LambdaQueryWrapper 完成，不需要自定义 SQL。
 */
@Mapper
public interface EvalRunMapper extends BaseMapper<EvalRunEntity> {
}

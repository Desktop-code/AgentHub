package com.agenthub.prompt.infra.persistence.mapper;

import com.agenthub.prompt.infra.persistence.entity.PromptConstraintEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * prompt_global_constraint 映射。所属域：prompt。
 */
@Mapper
public interface PromptConstraintMapper extends BaseMapper<PromptConstraintEntity> {
}

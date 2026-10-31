package com.agenthub.prompt.infra.persistence.mapper;

import com.agenthub.prompt.infra.persistence.entity.PromptTemplateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * prompt_template 映射。所属域：prompt。
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplateEntity> {
}

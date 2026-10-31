package com.agenthub.model.infra.persistence.mapper;

import com.agenthub.model.infra.persistence.entity.ModelProviderEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * model_provider 映射。所属域：model。
 */
@Mapper
public interface ModelProviderMapper extends BaseMapper<ModelProviderEntity> {
}

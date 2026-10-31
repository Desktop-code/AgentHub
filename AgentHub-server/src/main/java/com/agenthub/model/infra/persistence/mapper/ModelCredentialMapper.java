package com.agenthub.model.infra.persistence.mapper;

import com.agenthub.model.infra.persistence.entity.ModelCredentialEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * model_credential 映射。所属域：model。
 */
@Mapper
public interface ModelCredentialMapper extends BaseMapper<ModelCredentialEntity> {
}

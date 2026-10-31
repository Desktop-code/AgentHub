package com.agenthub.obs.infra.persistence.mapper;

import com.agenthub.obs.infra.persistence.entity.ObsSpanEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * obs_span 映射。所属域：obs。
 */
@Mapper
public interface ObsSpanMapper extends BaseMapper<ObsSpanEntity> {
}

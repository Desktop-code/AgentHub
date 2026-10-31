package com.agenthub.obs.infra.persistence.mapper;

import com.agenthub.obs.infra.persistence.entity.ObsTraceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * obs_trace 映射。所属域：obs。
 */
@Mapper
public interface ObsTraceMapper extends BaseMapper<ObsTraceEntity> {
}

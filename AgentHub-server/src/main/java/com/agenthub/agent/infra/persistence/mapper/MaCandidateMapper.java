package com.agenthub.agent.infra.persistence.mapper;

import com.agenthub.agent.infra.persistence.entity.MaCandidateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * ma_candidate 映射。所属域：agent。
 */
@Mapper
public interface MaCandidateMapper extends BaseMapper<MaCandidateEntity> {
}

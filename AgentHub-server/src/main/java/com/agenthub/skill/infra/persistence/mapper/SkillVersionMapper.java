package com.agenthub.skill.infra.persistence.mapper;

import com.agenthub.skill.infra.persistence.entity.SkillVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * skill_version 映射。所属域：skill。
 */
@Mapper
public interface SkillVersionMapper extends BaseMapper<SkillVersionEntity> {
}

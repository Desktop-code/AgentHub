package com.agenthub.skill.infra.persistence.mapper;

import com.agenthub.skill.infra.persistence.entity.SkillDefEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * skill_def 映射。所属域：skill。
 */
@Mapper
public interface SkillDefMapper extends BaseMapper<SkillDefEntity> {
}

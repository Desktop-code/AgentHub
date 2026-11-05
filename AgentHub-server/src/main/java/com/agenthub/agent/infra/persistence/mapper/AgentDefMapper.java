package com.agenthub.agent.infra.persistence.mapper;

import com.agenthub.agent.infra.persistence.entity.AgentDefEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * agent_def 映射。所属域：agent。
 */
@Mapper
public interface AgentDefMapper extends BaseMapper<AgentDefEntity> {

    /**
     * 自定义 SQL 不套 @TableLogic，可读到已软删行，供用量页历史拼名。
     *
     * @param id Agent 主键
     * @return 行实体，不存在则 null
     */
    @Select("SELECT * FROM agent_def WHERE id = #{id}")
    AgentDefEntity selectByIdIncludingDeleted(@Param("id") Long id);
}

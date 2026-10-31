package com.agenthub.model.infra.persistence.mapper;

import com.agenthub.model.infra.persistence.entity.ModelInstanceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * model_instance 映射。所属域：model。
 */
@Mapper
public interface ModelInstanceMapper extends BaseMapper<ModelInstanceEntity> {

    /**
     * 自定义 SQL 不套 @TableLogic，可读到已软删行，供用量页历史拼名。
     *
     * @param id 实例主键
     * @return 行实体，不存在则 null
     */
    @Select("SELECT * FROM model_instance WHERE id = #{id}")
    ModelInstanceEntity selectByIdIncludingDeleted(@Param("id") Long id);
}

package com.agenthub.workflow.infra.persistence.mapper;

import com.agenthub.workflow.infra.persistence.entity.AppWorkflowGrantEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * app_workflow_grant 映射。所属域：workflow。
 */
@Mapper
public interface AppWorkflowGrantMapper extends BaseMapper<AppWorkflowGrantEntity> {
}

package com.agenthub.workflow.infra.persistence.mapper;

import com.agenthub.workflow.infra.persistence.entity.WorkflowNodeRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * workflow_node_run 基础 CRUD。所属域：workflow 的 infra。
 */
@Mapper
public interface WorkflowNodeRunMapper extends BaseMapper<WorkflowNodeRunEntity> {
}

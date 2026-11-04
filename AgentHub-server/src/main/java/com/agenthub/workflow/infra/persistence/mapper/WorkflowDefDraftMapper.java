package com.agenthub.workflow.infra.persistence.mapper;

import com.agenthub.workflow.infra.persistence.entity.WorkflowDefDraftEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * workflow_def_draft 映射。所属域：workflow。
 */
@Mapper
public interface WorkflowDefDraftMapper extends BaseMapper<WorkflowDefDraftEntity> {
}

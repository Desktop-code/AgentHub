package com.agenthub.workflow.infra.persistence.mapper;

import com.agenthub.workflow.infra.persistence.entity.WorkflowReleaseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * workflow_release 映射。所属域：workflow。只追加，不更新历史行。
 */
@Mapper
public interface WorkflowReleaseMapper extends BaseMapper<WorkflowReleaseEntity> {
}

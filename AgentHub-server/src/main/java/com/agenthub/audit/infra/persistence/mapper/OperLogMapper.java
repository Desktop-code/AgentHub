package com.agenthub.audit.infra.persistence.mapper;

import com.agenthub.audit.infra.persistence.entity.SysOperLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_oper_log 映射。所属域：audit。
 */
@Mapper
public interface OperLogMapper extends BaseMapper<SysOperLogEntity> {
}

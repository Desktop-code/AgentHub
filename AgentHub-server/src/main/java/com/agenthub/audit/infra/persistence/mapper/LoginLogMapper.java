package com.agenthub.audit.infra.persistence.mapper;

import com.agenthub.audit.infra.persistence.entity.SysLoginLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * sys_login_log 映射。所属域：audit。
 */
@Mapper
public interface LoginLogMapper extends BaseMapper<SysLoginLogEntity> {
}

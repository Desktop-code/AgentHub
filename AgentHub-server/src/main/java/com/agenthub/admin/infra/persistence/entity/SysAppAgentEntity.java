package com.agenthub.admin.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_app_agent。所属域：admin。agent_id 没有外键，本期不校验 Agent 是否存在。
 * 时间列：create_time。
 */
@Data
@TableName("sys_app_agent")
public class SysAppAgentEntity {

    @TableId(value = "app_id", type = IdType.INPUT)
    private Long appId;
    private Long agentId;
    private LocalDateTime createTime;
}

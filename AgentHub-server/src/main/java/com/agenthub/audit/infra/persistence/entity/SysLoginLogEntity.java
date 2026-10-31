package com.agenthub.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_login_log。所属域：audit。没有逻辑删除列。
 * 时间列：create_time。
 */
@Data
@TableName("sys_login_log")
public class SysLoginLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private Integer loginType;
    private String ip;
    private String userAgent;
    private Integer success;
    private String message;
    private LocalDateTime createTime;
}

package com.agenthub.audit.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_oper_log。所属域：audit。detail_json 只存摘要，表本身也不放对话轨迹。
 * 时间列：create_time。
 */
@Data
@TableName("sys_oper_log")
public class SysOperLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private Long deptId;
    private String module;
    private String action;
    private String targetType;
    private String targetId;
    private String requestMethod;
    private String requestUrl;
    private String ip;
    private Integer success;
    private String errorMsg;
    private String detailJson;
    private Integer costMs;
    private LocalDateTime createTime;
}

package com.agenthub.audit.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 控制台操作日志。所属域：audit。detail 是变更摘要，不是请求体。
 */
@Data
public class OperLog {

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
    private LocalDateTime createTime;
}

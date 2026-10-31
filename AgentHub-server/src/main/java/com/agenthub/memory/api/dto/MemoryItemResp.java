package com.agenthub.memory.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记忆条目。所属域：memory。列表和详情都带正文，供控制台修改。
 */
@Data
public class MemoryItemResp {

    private Long id;
    private Long agentId;
    private Long userId;
    private Long appId;
    private Long sessionId;
    private String scope;
    private String title;
    /** LONG 稳定事实键；SHORT/AGENT 多为空 */
    private String factKey;
    private String content;
    private Integer importance;
    private Integer status;
    private LocalDateTime expiredTime;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

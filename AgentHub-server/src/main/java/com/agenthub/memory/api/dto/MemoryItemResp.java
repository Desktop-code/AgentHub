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
    /**
     * 向量同步状态：PENDING 待同步 / SYNCED 已同步 / FAILED 同步失败。
     * 只读展示，不允许通过条目接口改写。
     */
    private String vectorSyncStatus;
    /** 连续失败次数；仅 FAILED 时有展示意义 */
    private Integer vectorSyncFailCount;
    /** 最近一次失败原因（后端已脱敏、截断）；成功/终态为空 */
    private String vectorSyncError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

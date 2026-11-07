package com.agenthub.memory.domain.model;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 记忆条目。所属域：memory。对应 memory_item。
 * content 禁止密钥，也不进入操作日志。
 */
@Data
public class MemoryItem {

    private Long id;
    private Long agentId;
    private Long userId;
    private Long appId;
    private Long sessionId;
    private String scope;
    private String title;
    /** LONG 稳定事实键；SHORT/AGENT 可空；同 agent+user+LONG+key 合并更新 */
    private String factKey;
    @ToString.Exclude
    private String content;
    private String contentHash;
    private Integer importance;
    private Integer status;
    private LocalDateTime expiredTime;
    private String remark;
    /**
     * 向量同步状态（PENDING / SYNCED / FAILED）。只靠状态位，不记同步时间。
     * 见 {@link MemoryVectorSyncStatus}：写/改 → PENDING，成功或无需动作 → SYNCED。
     */
    private String vectorSyncStatus;
    /** 连续失败次数；达到上限后不再被补同步捞取 */
    private Integer vectorSyncFailCount;
    /** 最近一次失败原因（脱敏、截断，禁止存正文与密钥） */
    private String vectorSyncError;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

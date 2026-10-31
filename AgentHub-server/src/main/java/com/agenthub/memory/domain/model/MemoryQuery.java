package com.agenthub.memory.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记忆列表筛选。所属域：memory。控制台以数据库为准，不读 Redis。
 * 控制台查询应带 agentId + scope，避免无维度全表扫描。
 */
@Data
public class MemoryQuery {

    private long current = 1;
    private long size = 20;
    private Long agentId;
    private String scope;
    private Long sessionId;
    private Long userId;
    /** 兼容旧筛选：1 启用 / 0 停用；与 lifeStatus 同时传时以 lifeStatus 为准。 */
    private Integer status;
    /**
     * 生命周期筛选：NORMAL=启用且未过期；EXPIRED=已过期；DELETED=逻辑删除。
     * 空表示不按生命周期过滤（仍受 TableLogic 默认排除已删）。
     */
    private String lifeStatus;
    private LocalDateTime createTimeFrom;
    private LocalDateTime createTimeTo;
}

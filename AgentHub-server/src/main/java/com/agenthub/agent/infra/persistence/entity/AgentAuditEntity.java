package com.agenthub.agent.infra.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 配置/绑定表共用的审计列。所属域：agent 的 infra。
 * 不继承 admin 的审计基类，避免配置域依赖管理域。
 * 列名对齐定稿：create_time / update_time（非 created_at）。
 * 不含 agent_run_*（runtime 另刀）。
 */
@Data
public abstract class AgentAuditEntity {

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}

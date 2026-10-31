package com.agenthub.rag.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 与知识库的绑定。所属域：rag。对应 agent_kb_bind。
 * status 停用后该库不进运行时检索，也不出现在 RAG 工具的可访问列表里。
 */
@Data
public class AgentKbBind {

    /** 主键 */
    private Long id;
    /** 租户 */
    private Long tenantId;
    /** Agent */
    private Long agentId;
    /** 知识库 */
    private Long kbId;
    /** 1 启用 0 停用 */
    private Integer status;
    /** 检索覆盖项 JSON，可空 */
    private String overridesJson;
    /** 创建人 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新人 */
    private Long updatedBy;
    /** 更新时间 */
    private LocalDateTime updateTime;
}

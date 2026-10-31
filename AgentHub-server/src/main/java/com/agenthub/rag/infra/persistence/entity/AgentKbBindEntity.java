package com.agenthub.rag.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent_kb_bind。所属域：rag 的 infra（表归属 RAG，因为它是知识库的访问授权面）。
 * agent_id 指向 agent_def.id，kb_id 指向 rag_knowledge_base.id，均不建外键。
 * overrides_json 是该 Agent 的检索覆盖项，空则全用 KB 默认。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_kb_bind")
public class AgentKbBindEntity extends RagAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long agentId;
    private Long kbId;
    private Integer status;
    /** 检索覆盖项 JSON，可空 */
    private String overridesJson;

    @TableLogic
    private Integer deleted;
}

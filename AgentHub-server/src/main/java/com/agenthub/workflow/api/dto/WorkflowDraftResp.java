package com.agenthub.workflow.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 草稿出参 / 保存后回显。所属域：workflow。
 * <p>graph 原样回传 nodes / edges / viewport；updateTime 供下次 PUT 做乐观锁。
 */
@Data
public class WorkflowDraftResp {

    private Long workflowId;
    /** 图 JSON 树 */
    private JsonNode graph;
    /** 更新时间（yyyy-MM-dd HH:mm:ss.SSS），PUT 时作为 expectedUpdateTime */
    private LocalDateTime updateTime;
}

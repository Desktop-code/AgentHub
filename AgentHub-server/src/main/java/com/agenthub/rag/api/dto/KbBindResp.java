package com.agenthub.rag.api.dto;

import lombok.Data;

/**
 * Agent 知识库绑定响应。所属域：rag。
 * 回填知识库名称与状态，让 Agent 配置页不必再查一次 KB 列表。
 */
@Data
public class KbBindResp {

    private Long id;
    private Long agentId;
    private Long kbId;
    /** 1 启用 0 停用 */
    private Integer status;
    /** 检索覆盖项 JSON，可空 */
    private String overridesJson;
    /** 知识库名称；库已删时为 null */
    private String kbName;
    /** 知识库状态：READY / INIT_FAILED / DISABLED */
    private String kbStatus;
    /** DENSE_BM25 / DENSE_ONLY */
    private String kbCapability;
    /** PUBLIC / PRIVATE */
    private String kbVisibility;
    private Integer vectorDimension;
}

package com.agenthub.workflow.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/**
 * 某个已发布版本 START 节点上的入参声明。所属域：workflow（api DTO）。
 * <p>用途：画布 SUBGRAPH 节点按「钉死的那个版本」出槽；读的是该版本的 graph_json，
 * 不是对方当前草稿，也不是现在的默认发布版。
 * <p>本刀不返回整份图，避免把被调工作流的内部结构带进调用方画布。
 */
@Data
public class WorkflowReleaseInputSchemaResp {

    /** 被调工作流定义 id */
    private Long workflowId;
    /** 发布版本 id（钉死的那条） */
    private Long releaseId;
    /** 版本号，用于展示 */
    private Integer version;
    /** 声明对象；该版本没有声明时为 null（画布保持自由添加，不编造槽） */
    private JsonNode inputSchema;
    /** 声明里的必填键（properties ∩ required），画布据此标必填与缺失提示 */
    private List<String> requiredKeys;
}

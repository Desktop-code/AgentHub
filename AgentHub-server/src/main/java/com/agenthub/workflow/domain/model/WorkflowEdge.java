package com.agenthub.workflow.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 工作流边（校验用视图）。所属域：workflow 的 domain。
 * <p>source / target 存节点 id；type 为 NORMAL / COND / DEFAULT / ERROR / CANCEL。
 */
@Data
public class WorkflowEdge {

    /** 边 id，图内唯一 */
    private String id;
    /** 起点节点 id */
    private String source;
    /** 终点节点 id */
    private String target;
    /** 边类型原文（解析失败由校验器报错） */
    private String type;
    /** 展示标签；条件边可放中文条件说明 */
    private String label;
    /** 边配置 JSON；可能为 null */
    private JsonNode config;
}

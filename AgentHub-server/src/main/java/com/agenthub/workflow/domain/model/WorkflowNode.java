package com.agenthub.workflow.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 工作流节点（校验用视图）。所属域：workflow 的 domain。
 * <p>id 是画布内唯一句柄；code 是业务编码且图内唯一；config 保留原始 JSON 供
 * 绑定校验（agentId / toolId / forkGroupId / timeoutPolicy / 映射路径）读取。
 */
@Data
public class WorkflowNode {

    /** 画布内唯一 id */
    private String id;
    /** 业务编码，图内唯一 */
    private String code;
    /** 节点类型原文（解析失败由校验器报错） */
    private String type;
    /** 展示名 */
    private String name;
    /** 节点配置 JSON；可能为 null */
    private JsonNode config;
    /** 画布 x 坐标 */
    private double uiX;
    /** 画布 y 坐标 */
    private double uiY;
}

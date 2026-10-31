package com.agenthub.runtime.domain.model;

import lombok.Data;

/**
 * 要注册进 AgentScope Toolkit 的 MCP 工具。所属域：runtime。
 * 仅已绑定且 status=1 的项会进入本结构；执行时按 sourceId 解密鉴权并发 tools/call。
 */
@Data
public class McpToolSpec {

    /** tool_item.name，同时是模型侧调用名 */
    private String name;
    /** 一句话说明，给 AgentTool description */
    private String description;
    /** 同步写入的 input_schema_json；空则注册时用占位 object schema */
    private String inputSchemaJson;
    /** tool_source.id，出站取 base_url / 鉴权头 */
    private Long sourceId;
}

package com.agenthub.tool.domain.model;

/**
 * 工具源类型。所属域：tool。BUILTIN 与 MCP 进同一目录，靠本枚举区分。
 */
public enum ToolSourceType {

    /** 平台种子，卡片只读，不能经新建接口创建 */
    BUILTIN,
    /** 远端 MCP，一期只走 SSE */
    MCP
}

package com.agenthub.tool.domain.model;

/**
 * MCP 传输。所属域：tool。
 * 本阶段仅 {@link #SSE} 与 {@link #HTTP}；stdio / WebSocket 不进入本枚举。
 */
public enum ToolTransport {

    /** MCP HTTP + Server-Sent Events（协议 2024-11-05 旧传输） */
    SSE,

    /**
     * MCP Streamable HTTP（协议 2025-03-26 起）。
     * 单端点 POST JSON-RPC；可选 {@code Mcp-Session-Id}；响应可为 JSON 或 SSE 流。
     * 不是 OpenAPI/普通 REST，也不是 stdio。
     */
    HTTP
}

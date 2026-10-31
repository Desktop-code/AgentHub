package com.agenthub.tool.infra.mcp;

import java.util.List;

/**
 * MCP 出站统一口。所属域：tool 的 infra。
 * 实现按传输分：{@link McpSseClient}（旧 HTTP+SSE）与 {@link McpHttpClient}（Streamable HTTP）。
 * 调用方只给地址和已解密的鉴权头；同步走 {@link #listTools}；试跑真调走 {@link #callTool}。
 */
public interface McpClient {

    /**
     * @param baseUrl    MCP 入口（SSE 路径或 Streamable HTTP 端点），http 或 https
     * @param authHeader 整段头，如 {@code Authorization: Bearer token}；可空
     * @return 远端工具，不含密钥
     */
    List<McpToolView> listTools(String baseUrl, String authHeader);

    /**
     * 对已握手的 MCP 会话发起一次 {@code tools/call}。
     *
     * @param baseUrl       MCP 入口
     * @param authHeader    已解密鉴权头，可空
     * @param toolName      远端工具名（与 tools/list 的 name 一致）
     * @param argumentsJson 实参 JSON 对象文本；空则按 {@code {}}
     * @return 可供模型阅读的结果正文（不含鉴权头/密钥）
     */
    String callTool(String baseUrl, String authHeader, String toolName, String argumentsJson);
}

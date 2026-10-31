package com.agenthub.tool.infra.mcp;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.tool.domain.model.ToolTransport;
import org.springframework.stereotype.Component;

/**
 * 按传输选择 MCP 客户端。所属域：tool。
 * SSE → {@link McpSseClient}；HTTP → {@link McpHttpClient}（Streamable HTTP）。
 */
@Component
public class McpClientFactory {

    private final McpSseClient sseClient;
    private final McpHttpClient httpClient;

    /**
     * @param sseClient  旧 HTTP+SSE
     * @param httpClient Streamable HTTP
     */
    public McpClientFactory(McpSseClient sseClient, McpHttpClient httpClient) {
        this.sseClient = sseClient;
        this.httpClient = httpClient;
    }

    /**
     * 空 transport 按 SSE，与存量源对齐。
     *
     * @param transport 源上的传输名
     * @return 对应客户端
     * @throws BizException 非 SSE/HTTP 时
     */
    public McpClient require(String transport) {
        String normalized = transport == null || transport.isBlank()
                ? ToolTransport.SSE.name()
                : transport.trim();
        if (ToolTransport.SSE.name().equalsIgnoreCase(normalized)) {
            return sseClient;
        }
        if (ToolTransport.HTTP.name().equalsIgnoreCase(normalized)) {
            return httpClient;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "传输仅支持 SSE 或 HTTP");
    }
}

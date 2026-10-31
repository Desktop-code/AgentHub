package com.agenthub.tool.infra.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP SSE 同步的 HTTP 超时。所属域：tool。避免 tools/list 占住请求线程。
 */
@ConfigurationProperties(prefix = "agenthub.tool.mcp")
public class ToolMcpProperties {

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;

    /**
     * @return 建立连接的上限，毫秒
     */
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * @param connectTimeoutMs 毫秒
     */
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * @return 整次同步的上限，毫秒
     */
    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * @param readTimeoutMs 毫秒
     */
    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}

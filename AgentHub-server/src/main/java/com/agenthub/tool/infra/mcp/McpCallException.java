package com.agenthub.tool.infra.mcp;

/**
 * MCP 调用失败。所属域：tool 的 infra。
 * 文案由客户端写成不含地址和密钥的短句，应用层再脱敏后写入 last_sync_msg。
 */
public class McpCallException extends RuntimeException {

    /**
     * @param message 可展示的失败原因
     */
    public McpCallException(String message) {
        super(message);
    }
}

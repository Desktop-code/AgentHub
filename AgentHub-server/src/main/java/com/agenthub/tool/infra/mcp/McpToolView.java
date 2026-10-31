package com.agenthub.tool.infra.mcp;

/**
 * tools/list 中的一条。所属域：tool 的 infra。schema 已收成 JSON 文本。
 *
 * @param name            调用名
 * @param title           展示名，可空
 * @param description     说明，可空
 * @param inputSchemaJson JSON Schema，可空
 */
public record McpToolView(String name, String title, String description, String inputSchemaJson) {
}

package com.agenthub.tool.domain.model;

/**
 * 远端 tools/list 里的一条工具。所属域：tool。不含鉴权头。
 *
 * @param name            调用名，同步键
 * @param title           展示名，可空
 * @param description     说明，可空
 * @param inputSchemaJson JSON Schema 文本，可空
 */
public record RemoteTool(String name, String title, String description, String inputSchemaJson) {
}

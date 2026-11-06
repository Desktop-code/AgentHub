package com.agenthub.tool.infra.mcp;

import com.agenthub.common.text.TextClip;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP JSON-RPC 的组包和 tools/list 解析。所属域：tool 的 infra。
 * 协议版本固定 2024-11-05。调用名超过 128 的条目丢掉，避免截断后撞名。
 */
final class McpJson {

    private static final int NAME_MAX = 128;
    private static final int TITLE_MAX = 128;
    private static final int DESC_MAX = 1000;
    private static final int SCHEMA_MAX = 60000;

    private McpJson() {
    }

    /** SSE 传输沿用的协议版本 */
    static final String PROTOCOL_SSE = "2024-11-05";

    /** Streamable HTTP 引入时的协议版本 */
    static final String PROTOCOL_HTTP = "2025-03-26";

    /**
     * SSE 握手用 initialize（协议 2024-11-05）。
     *
     * @param json 序列化器
     * @return initialize 请求
     */
    static ObjectNode initialize(ObjectMapper json) {
        return initialize(json, PROTOCOL_SSE);
    }

    /**
     * @param json            序列化器
     * @param protocolVersion 客户端声明的协议版本
     * @return initialize 请求（id 固定为 1，与会话内 nextId 起点对齐）
     */
    static ObjectNode initialize(ObjectMapper json, String protocolVersion) {
        ObjectNode root = json.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", 1);
        root.put("method", "initialize");
        ObjectNode params = root.putObject("params");
        params.put("protocolVersion", protocolVersion == null || protocolVersion.isBlank() ? PROTOCOL_SSE : protocolVersion);
        params.putObject("capabilities");
        ObjectNode info = params.putObject("clientInfo");
        info.put("name", "agenthub");
        info.put("version", "0.1");
        return root;
    }

    /**
     * @param json 序列化器
     * @return 无 id 的 initialized 通知
     */
    static ObjectNode initialized(ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("method", "notifications/initialized");
        return root;
    }

    /**
     * @param json   序列化器
     * @param id     请求 id
     * @param cursor 上一页游标，首页为 null
     * @return tools/list 请求
     */
    static ObjectNode toolsList(ObjectMapper json, long id, String cursor) {
        ObjectNode root = json.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        root.put("method", "tools/list");
        ObjectNode params = root.putObject("params");
        if (cursor != null && !cursor.isBlank()) {
            params.put("cursor", cursor);
        }
        return root;
    }

    /**
     * @param json           序列化器
     * @param id             请求 id
     * @param toolName       远端工具名
     * @param argumentsJson  实参对象 JSON；空白按空对象
     * @return tools/call 请求
     */
    static ObjectNode toolsCall(ObjectMapper json, long id, String toolName, String argumentsJson) {
        ObjectNode root = json.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", id);
        root.put("method", "tools/call");
        ObjectNode params = root.putObject("params");
        params.put("name", toolName == null ? "" : toolName);
        params.set("arguments", argumentsNode(json, argumentsJson));
        return root;
    }

    /**
     * 把 tools/call 的 result 收成短文本；isError 时抛可展示文案，不附带原始鉴权信息。
     *
     * @param json   序列化器
     * @param result tools/call 的 result 节点
     * @return 文本内容拼接，或整段 JSON 兜底
     */
    static String callOutput(ObjectMapper json, JsonNode result) {
        if (result == null || result.isNull()) {
            throw new McpCallException("MCP 调用返回空结果");
        }
        if (result.path("isError").asBoolean(false)) {
            throw new McpCallException(TextClip.raw(textContent(result), 200));
        }
        String text = textContent(result);
        if (text != null && !text.isBlank()) {
            return text;
        }
        try {
            return json.writeValueAsString(result);
        } catch (Exception ex) {
            return "{\"ok\":true}";
        }
    }

    private static JsonNode argumentsNode(ObjectMapper json, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(argumentsJson);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (Exception ignored) {
            // 非法 JSON 按空对象，避免把坏实参原文打进 MCP 请求日志路径
        }
        return json.createObjectNode();
    }

    private static String textContent(JsonNode result) {
        JsonNode content = result.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            JsonNode message = result.get("message");
            return message == null || message.isNull() ? "MCP 工具调用失败" : message.asText("MCP 工具调用失败");
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode block : content) {
            if (block == null) {
                continue;
            }
            String type = block.path("type").asText("");
            if ("text".equals(type) || type.isEmpty()) {
                String piece = text(block.get("text"));
                if (piece != null) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(piece);
                }
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    /**
     * @param json 序列化器
     * @param text SSE data 或 POST 正文
     * @return 解析结果；不是 JSON 时为 null
     */
    static JsonNode tryParse(ObjectMapper json, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 有 error 时抛出短文案，不附带原始报文。
     *
     * @param node JSON-RPC 响应
     * @return result 节点
     */
    static JsonNode unwrap(JsonNode node) {
        if (node.hasNonNull("error")) {
            String msg = node.get("error").path("message").asText("MCP 返回错误");
            throw new McpCallException(TextClip.raw(msg, 200));
        }
        JsonNode result = node.get("result");
        if (result == null || result.isNull()) {
            throw new McpCallException("MCP 返回空结果");
        }
        return result;
    }

    /**
     * @param json   序列化器
     * @param result tools/list 的 result
     * @return 可入库的工具；超长调用名跳过
     */
    static List<McpToolView> tools(ObjectMapper json, JsonNode result) {
        JsonNode rows = result == null ? null : result.get("tools");
        if (rows == null || !rows.isArray()) {
            throw new McpCallException("MCP 未返回工具列表");
        }
        List<McpToolView> list = new ArrayList<>();
        for (JsonNode row : rows) {
            String name = text(row.get("name"));
            if (name == null || name.length() > NAME_MAX) {
                continue;
            }
            String title = text(row.get("title"));
            if (title == null && row.has("annotations")) {
                title = text(row.get("annotations").get("title"));
            }
            list.add(new McpToolView(name, TextClip.raw(title, TITLE_MAX), TextClip.raw(text(row.get("description")), DESC_MAX), schema(json, row.get("inputSchema"))));
        }
        return list;
    }

    /**
     * 超长 schema 不截半截 JSON，改存一条说明，列里仍是合法 JSON。
     */
    private static String schema(ObjectMapper json, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            String text = json.writeValueAsString(node);
            if (text.length() <= SCHEMA_MAX) {
                return text;
            }
            return "{\"truncated\":true}";
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

}

package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * graph_json 解析与最小骨架生成。所属域：workflow 的 application。
 * <p>职责：把画布 JSON 转成校验用 {@link WorkflowGraph}，并在新建定义时生成
 * 「1 START + 1 END」的最小合法骨架。
 * <p>边界：这里只做 JSON 结构校验（对象/数组/字段类型），业务约束（环、DEFAULT、
 * FORK/JOIN、绑定）一律交给 {@code WorkflowGraphValidator}，避免两处口径漂移。
 */
@Component
public class WorkflowGraphCodec {

    /** 单图 JSON 字符上限（UTF-8 字节）；超出直接拒绝，避免脏包拖垮校验 */
    private static final int MAX_GRAPH_BYTES = 512 * 1024;

    private final ObjectMapper json;

    /**
     * @param json 全站共用的 ObjectMapper
     */
    public WorkflowGraphCodec(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 结构校验并解析图。
     *
     * @param raw 原始 JSON 文本
     * @return 校验用图；空图返回空 nodes / edges
     * @throws BizException JSON 非法、结构不对或体积超限（BAD_REQUEST）
     */
    public WorkflowGraph parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图 JSON 不能为空");
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_GRAPH_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "图 JSON 过大（上限 " + (MAX_GRAPH_BYTES / 1024) + "KB）");
        }
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图 JSON 不是合法 JSON");
        }
        return parse(root);
    }

    /**
     * 结构校验并解析图。
     *
     * @param root 已解析的 JSON 树
     * @return 校验用图
     * @throws BizException 不是对象、nodes/edges 不是数组、元素不是对象（BAD_REQUEST）
     */
    public WorkflowGraph parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图 JSON 顶层必须是对象");
        }
        WorkflowGraph graph = new WorkflowGraph();
        JsonNode nodes = root.get("nodes");
        if (nodes != null && !nodes.isNull()) {
            if (!nodes.isArray()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图 JSON 的 nodes 必须是数组");
            }
            int index = 0;
            for (JsonNode item : nodes) {
                graph.getNodes().add(toNode(item, index));
                index++;
            }
        }
        JsonNode edges = root.get("edges");
        if (edges != null && !edges.isNull()) {
            if (!edges.isArray()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图 JSON 的 edges 必须是数组");
            }
            int index = 0;
            for (JsonNode item : edges) {
                graph.getEdges().add(toEdge(item, index));
                index++;
            }
        }
        return graph;
    }

    /**
     * 读取已落库的图 JSON（给详情接口回显）。
     *
     * @param raw 库里的 graph_json
     * @return JSON 树
     * @throws BizException 库内容损坏（BAD_REQUEST，提示重新保存）
     */
    public JsonNode read(String raw) {
        try {
            JsonNode root = json.readTree(raw == null ? "" : raw);
            return root == null ? json.createObjectNode() : root;
        } catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "草稿图 JSON 已损坏，请重新保存草稿");
        }
    }

    /**
     * 序列化图 JSON 用于落库。
     *
     * @param root 请求体 JSON 树
     * @return 紧凑 JSON
     * @throws BizException 结构不合法（BAD_REQUEST）
     */
    public String write(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图 JSON 顶层必须是对象");
        }
        if (!root.has("nodes") || !root.get("nodes").isArray()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图 JSON 必须包含 nodes 数组");
        }
        // edges 允许省略；缺省按空数组落库，避免前端空图保存失败
        ObjectNode copy = root.deepCopy();
        if (!copy.has("edges") || copy.get("edges").isNull()) {
            copy.set("edges", json.createArrayNode());
        } else if (!copy.get("edges").isArray()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图 JSON 的 edges 必须是数组");
        }
        try {
            return json.writeValueAsString(copy);
        } catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图 JSON 序列化失败");
        }
    }

    /**
     * 新建定义时的最小合法骨架：1 个 START + 1 个 END + 1 条 NORMAL 边。
     * <p>推荐做法：新图直接可校验通过，用户不会一进编辑页就看到满屏 error。
     *
     * @return 图 JSON 文本
     */
    public String defaultGraph() {
        ObjectNode root = json.createObjectNode();
        ArrayNode nodes = root.putArray("nodes");
        nodes.add(node("n1", "start", "START", "开始", 80, 180));
        nodes.add(node("n2", "end", "END", "结束", 520, 180));
        ArrayNode edges = root.putArray("edges");
        ObjectNode edge = edges.addObject();
        edge.put("id", "e1");
        edge.put("source", "n1");
        edge.put("target", "n2");
        edge.put("type", "NORMAL");
        edge.putNull("label");
        edge.set("config", json.createObjectNode());
        ObjectNode viewport = root.putObject("viewport");
        viewport.put("x", 0);
        viewport.put("y", 0);
        viewport.put("zoom", 1);
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("默认图序列化失败", ex);
        }
    }

    private ObjectNode node(String id, String code, String type, String name, double x, double y) {
        ObjectNode node = json.createObjectNode();
        node.put("id", id);
        node.put("code", code);
        node.put("type", type);
        node.put("name", name);
        node.set("config", json.createObjectNode());
        ObjectNode ui = node.putObject("ui");
        ui.put("x", x);
        ui.put("y", y);
        return node;
    }

    private WorkflowNode toNode(JsonNode item, int index) {
        if (item == null || !item.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "nodes[" + index + "] 必须是对象");
        }
        WorkflowNode node = new WorkflowNode();
        node.setId(text(item, "id"));
        node.setCode(text(item, "code"));
        node.setType(text(item, "type"));
        node.setName(text(item, "name"));
        node.setConfig(objectOrNull(item.get("config"), "nodes[" + index + "].config"));
        JsonNode ui = item.get("ui");
        if (ui != null && ui.isObject()) {
            node.setUiX(ui.path("x").asDouble(0));
            node.setUiY(ui.path("y").asDouble(0));
        }
        return node;
    }

    private WorkflowEdge toEdge(JsonNode item, int index) {
        if (item == null || !item.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "edges[" + index + "] 必须是对象");
        }
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(text(item, "id"));
        edge.setSource(text(item, "source"));
        edge.setTarget(text(item, "target"));
        edge.setType(text(item, "type"));
        edge.setLabel(text(item, "label"));
        edge.setConfig(objectOrNull(item.get("config"), "edges[" + index + "].config"));
        return edge;
    }

    private String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        // 画布可能把 id 写成数字；统一按文本读，避免因类型差异误判悬空
        return value.isTextual() || value.isNumber() ? value.asText() : null;
    }

    private JsonNode objectOrNull(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + " 必须是对象");
        }
        return value;
    }
}

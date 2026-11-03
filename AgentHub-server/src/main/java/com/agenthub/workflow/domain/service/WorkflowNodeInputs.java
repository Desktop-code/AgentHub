package com.agenthub.workflow.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 节点入参 / 出参映射解析。所属域：workflow 的 domain。
 * <p>约定（定稿 §3.4）：映射值是路径 {@code $.input.x} / {@code $.outputs.nodeCode.x} /
 * {@code $.vars.k}；不以 {@code $} 开头时按字面量处理，便于「固定提示词」这类小需求。
 * <p>无映射时给保守默认：AGENT 优先取 input.message / input.query / input.text，
 * 都没有才把整个 input 序列化成文本；不会把 outputs 泄给下一个 Agent。
 */
public final class WorkflowNodeInputs {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowNodeInputs() {
    }

    /**
     * 解析一个路径值。
     *
     * @param ctx  上下文 {input, outputs, vars}
     * @param expr 路径或字面量
     * @return 解析结果；路径读不到返回 NullNode
     */
    public static JsonNode resolve(JsonNode ctx, String expr) {
        if (expr == null || expr.isBlank()) {
            return JSON.nullNode();
        }
        String text = expr.trim();
        if (!text.startsWith("$")) {
            return JSON.getNodeFactory().textNode(text);
        }
        JsonNode found = JsonLogicEvaluator.eval(
                JSON.getNodeFactory().objectNode().put("var", text.startsWith("$.") ? text.substring(2) : text),
                ctx, 0);
        return found == null ? JSON.nullNode() : found;
    }

    /**
     * 把映射表解析成对象。
     *
     * @param ctx     上下文
     * @param mapping 目标字段 → 路径；null / 空对象返回空对象
     * @return 解析后的对象
     */
    public static ObjectNode mapped(JsonNode ctx, JsonNode mapping) {
        ObjectNode out = JSON.createObjectNode();
        if (mapping == null || !mapping.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = mapping.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String target = entry.getKey();
            JsonNode pathNode = entry.getValue();
            if (target == null || target.isBlank() || pathNode == null || !pathNode.isTextual()) {
                continue;
            }
            out.set(target, resolve(ctx, pathNode.asText()));
        }
        return out;
    }

    /**
     * AGENT / TOOL 节点的「单一入参文本」。
     * <p>有映射且只有一项时直接取该项文本；多项时给 JSON，由模型自行理解结构化入参。
     * 无映射时回落到 input 的常用字段。
     *
     * @param ctx     上下文
     * @param mapping 映射表，可空
     * @return 非空文本
     */
    public static String message(JsonNode ctx, JsonNode mapping) {
        ObjectNode mapped = mapped(ctx, mapping);
        if (!mapped.isEmpty()) {
            if (mapped.size() == 1) {
                return text(mapped.elements().next());
            }
            return mapped.toString();
        }
        JsonNode input = ctx == null ? null : ctx.get("input");
        for (String key : new String[]{"message", "query", "text", "content"}) {
            JsonNode value = input == null ? null : input.get(key);
            if (value != null && !value.isNull() && !text(value).isBlank()) {
                return text(value);
            }
        }
        return input == null || input.isNull() ? "" : input.toString();
    }

    /**
     * 节点产出按 outputMapping 收口。
     * <p>没有映射时保留最小结构 {@code {text: ...}}，避免把整段内部对象写进 ctx 供后续节点误读。
     *
     * @param rawOutput 原始产出对象（至少含 text）
     * @param mapping   出参映射，可空
     * @return 落库 / 进 ctx 的产出
     */
    public static ObjectNode shapeOutput(ObjectNode rawOutput, JsonNode mapping) {
        ObjectNode source = rawOutput == null ? JSON.createObjectNode() : rawOutput;
        if (mapping == null || !mapping.isObject() || mapping.isEmpty()) {
            return source;
        }
        // 出参映射的取值域是「本节点原始产出的包装」：$.output.text 才能取到正文
        ObjectNode wrapper = JSON.createObjectNode();
        wrapper.set("output", source);
        ObjectNode shaped = mapped(wrapper, mapping);
        // 映射写错 / 全空时不要产出空对象，回退原始结构更利于排障
        return shaped.isEmpty() ? source : shaped;
    }

    /**
     * 节点文本化：字符串原样，其它类型给 JSON。
     *
     * @param node 任意节点
     * @return 文本；null 给空串
     */
    public static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }
}

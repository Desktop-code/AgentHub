package com.agenthub.agent.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 入参声明的归一与回显。所属域：agent 的 domain。
 * <p>「入参声明」是 Agent 对外的合同：键、展示名、是否必填、说明。存成顶层 JSON Schema
 * （{@code properties} / {@code required} / {@code title} / {@code description}），
 * 键就是编排里 {@code $.input} 下的字段名，与工作流开始节点的声明同一形态。
 * <p>为什么在这里归一而不是直接存前端原文：库列是 JSON，脏结构（非对象、超长、必填指向不存在的键）
 * 一旦落库，后续每个编排页的解析都要各自兜底；保存时统一收口一次更省事。
 * <p>纯函数、无状态、线程安全；坏数据读出时按「没有声明」处理，不向详情页抛异常。
 */
public final class AgentInputSchema {

    /** 单份声明最多多少个字段：画布槽位与详情页表格都不适合无限长 */
    private static final int MAX_FIELDS = 50;
    /** 键 / 展示名 / 说明的长度上限；与前端表格输入框一致 */
    private static final int MAX_KEY_CHARS = 64;
    private static final int MAX_TITLE_CHARS = 64;
    private static final int MAX_DESCRIPTION_CHARS = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentInputSchema() {
    }

    /**
     * 请求体 / 画布提交的声明 → 落库 JSON 文本。
     * <p>只保留 {@code properties[].title / description}（值类型固定为 string，因为运行入参是
     * {@code $.input} 下的文本字段）；{@code required} 只保留真的存在于 properties 的键，
     * 避免出现「必填但没有槽可配」的死配置。
     *
     * @param raw 声明 JSON 树；null / NullNode / 空 properties 表示没有声明
     * @return 归一后的 JSON 文本；没有声明时返回 null（清空列）
     * @throws BizException 顶层不是对象、字段过多、键为空 / 超长、字段定义不是对象
     */
    public static String normalize(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return null;
        }
        if (!raw.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "入参声明必须是 JSON 对象");
        }
        JsonNode properties = raw.get("properties");
        if (properties == null || properties.isNull()) {
            return null;
        }
        if (!properties.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "入参声明的 properties 必须是对象");
        }
        if (properties.size() > MAX_FIELDS) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "入参声明最多 " + MAX_FIELDS + " 个字段，请先精简");
        }
        Set<String> required = readRequired(raw.get("required"));
        ObjectNode canonical = JSON.createObjectNode();
        canonical.put("type", "object");
        ObjectNode outProperties = canonical.putObject("properties");
        ArrayNode outRequired = JSON.createArrayNode();
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "入参声明的键不能为空");
            }
            if (key.length() > MAX_KEY_CHARS) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "入参声明的键「" + key + "」超过 " + MAX_KEY_CHARS + " 个字符");
            }
            JsonNode definition = entry.getValue();
            if (definition != null && !definition.isNull() && !definition.isObject()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "入参声明的字段「" + key + "」必须是对象");
            }
            ObjectNode property = outProperties.putObject(key);
            property.put("type", "string");
            String title = readText(definition, "title", key, MAX_TITLE_CHARS, "展示名");
            if (!title.isEmpty()) {
                property.put("title", title);
            }
            String description = readText(definition, "description", key, MAX_DESCRIPTION_CHARS, "说明");
            if (!description.isEmpty()) {
                property.put("description", description);
            }
            // required 与 properties 取交集，并按 properties 顺序输出，保证同一份声明序列化稳定
            if (required.contains(key)) {
                outRequired.add(key);
            }
        }
        if (outProperties.isEmpty()) {
            return null;
        }
        if (!outRequired.isEmpty()) {
            canonical.set("required", outRequired);
        }
        try {
            return JSON.writeValueAsString(canonical);
        } catch (JsonProcessingException ex) {
            // 归一后的对象必然可序列化；走到这里说明 JDK / Jackson 环境异常
            throw new IllegalStateException("入参声明序列化失败", ex);
        }
    }

    /**
     * 库里的声明 JSON → 详情 / 列表回显对象。
     *
     * @param json 库里的 input_schema_json，可空
     * @return 声明对象；没有声明或内容已损坏时返回 null（页面按「无声明」处理）
     */
    public static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() && node.hasNonNull("properties") ? node : null;
        } catch (JsonProcessingException ex) {
            // 历史脏值不阻断详情页：当作没有声明，用户重新保存即可覆盖
            return null;
        }
    }

    /**
     * 声明里的必填键：{@code properties} 与 {@code required} 取交集。
     * <p>调用方：工作流校验器判断「AGENT 节点必填槽是否配了来源」。
     *
     * @param declaration 声明对象，可空
     * @return 必填键（按 required 顺序、去重）；无声明 / 结构不对返回空列表
     */
    public static List<String> requiredKeys(JsonNode declaration) {
        if (declaration == null || !declaration.isObject()) {
            return List.of();
        }
        JsonNode properties = declaration.get("properties");
        JsonNode required = declaration.get("required");
        if (properties == null || !properties.isObject() || required == null || !required.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : required) {
            if (!item.isTextual()) {
                continue;
            }
            String key = item.asText().trim();
            if (key.isEmpty() || !properties.has(key) || result.contains(key)) {
                continue;
            }
            result.add(key);
        }
        return result;
    }

    /** 读取 required 数组里的文本键，去空白、去重；缺失或非数组按空集合 */
    private static Set<String> readRequired(JsonNode required) {
        Set<String> result = new LinkedHashSet<>();
        if (required == null || !required.isArray()) {
            return result;
        }
        for (JsonNode item : required) {
            if (item.isTextual() && !item.asText().isBlank()) {
                result.add(item.asText().trim());
            }
        }
        return result;
    }

    /**
     * 读取字段定义里的文本属性并做长度校验。
     *
     * @param definition 字段定义对象，可空
     * @param field      title / description
     * @param key        字段键（用于中文报错）
     * @param max        长度上限
     * @param label      中文名
     * @return 去空白后的文本；没有给返回空串
     * @throws BizException 超长
     */
    private static String readText(JsonNode definition, String field, String key, int max, String label) {
        if (definition == null || !definition.isObject()) {
            return "";
        }
        JsonNode value = definition.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return "";
        }
        String text = value.asText().trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "入参声明字段「" + key + "」的" + label + "超过 " + max + " 个字符");
        }
        return text;
    }
}

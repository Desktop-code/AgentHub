package com.agenthub.obs.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * obs span/trace 的 attrs_json 小工具。所属域：obs。
 * <p>
 * 约定可选键：{@code brief}（人话摘要）、{@code cause}（脱敏失败原因），
 * 以及各业务域自行写入的扁平统计键（如注入统计；由 {@link #withFields} 合入）。
 * 无则不强编造。
 * <p>
 * 为什么合并要用 JSON 解析而不是字符串拼接：attrs 会被多次合入（brief → cause → injection），
 * 手拼字符串在「同名键重复」「值里带引号或换行」两种情况下都会产出非法 JSON，
 * 而这份 attrs 要落库并原样给前端展示。合并走 Jackson，同名键以<b>后写者</b>为准。
 * <p>
 * 依赖边界：本类只认「键 → 值」，不认识任何业务模型（不认识 runtime 的统计对象），
 * 由调用方把业务统计拍平成 Map 再传进来——obs 是横切域，不该反向依赖 runtime。
 * <p>
 * 线程安全：无状态，静态工具；共用一个不可变 {@link ObjectMapper}（线程安全）。
 */
public final class ObsAttrs {

    private static final Logger log = LoggerFactory.getLogger(ObsAttrs.class);
    /** Jackson 实例线程安全，仅供本类解析/合并 attrs 用 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObsAttrs() {
    }

    /**
     * 写入或覆盖 brief。
     *
     * @param existing 已有 attrs，可空
     * @param brief    人话摘要，可空
     * @return JSON；无 brief 时返回 existing
     */
    public static String withBrief(String existing, String brief) {
        if (brief == null || brief.isBlank()) {
            return existing;
        }
        return merge(existing, "brief", JSON.getNodeFactory().textNode(brief.trim()));
    }

    /**
     * @param attrsJson attrs_json
     * @return brief；没有则为 null
     */
    public static String briefOf(String attrsJson) {
        return textOf(attrsJson, "brief");
    }

    /**
     * 写入失败原因摘要（已脱敏的 cause）。
     *
     * <p>为什么不塞进 brief：brief 是「人话摘要」，已经用于展示成功路径的结论；
     * cause 是排障信息，长度和内容口径都不同（类名 + message + 少量帧），单独一个键更清楚，
     * 前端扩展信息区直接读 attrs_json 就能看到，不必改动 brief 的既有语义。
     *
     * @param existing 已有 attrs，可空
     * @param cause    已脱敏的 cause 摘要，可空
     * @return JSON；cause 为空时返回 existing
     */
    public static String withCause(String existing, String cause) {
        if (cause == null || cause.isBlank()) {
            return existing;
        }
        return merge(existing, "cause", JSON.getNodeFactory().textNode(cause.trim()));
    }

    /**
     * @param attrsJson attrs_json
     * @return cause 摘要；没有该字段时为 null
     */
    public static String causeOf(String attrsJson) {
        return textOf(attrsJson, "cause");
    }

    /**
     * 批量写入扁平字段。同名键以本次传入值为准（覆盖旧值），其它键原样保留。
     * <p>
     * 支持的值为 String / Number / Boolean；其它类型按 {@code toString()} 落成字符串，
     * 避免调用方为了写一个枚举再包一层。
     *
     * @param existing 已有 attrs，可空
     * @param fields   键 → 值；空 Map 时原样返回
     * @return 合并后的 JSON
     */
    public static String withFields(String existing, Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return existing;
        }
        ObjectNode node = objectOf(existing);
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            if (value instanceof Integer intValue) {
                node.put(key, intValue);
            } else if (value instanceof Long longValue) {
                node.put(key, longValue);
            } else if (value instanceof Boolean boolValue) {
                node.put(key, boolValue);
            } else if (value instanceof Double doubleValue) {
                node.put(key, doubleValue);
            } else {
                node.put(key, String.valueOf(value));
            }
        }
        return node.toString();
    }

    /**
     * 兼容旧签名的注入统计写入：只写汇总值、没有分段。
     * <p>
     * 新代码请用 {@link #withFields} 传入完整分段（见 runtime 侧
     * {@code PromptInjectionAttrs}），否则面板看不到「钱花在哪一段」。
     *
     * @param existing  已有 attrs，可空
     * @param tools     进入提示词的工具数
     * @param skills    进入提示词的 Skill 数
     * @param chars     各类字符合计
     * @param truncated 是否发生截断或降级
     * @return 合并后的 JSON
     */
    public static String withInjection(String existing, int tools, int skills, int chars, boolean truncated) {
        return withFields(existing, Map.of(
                "tools_injected", tools,
                "skills_injected", skills,
                "prompt_chars", chars,
                "truncated", truncated));
    }

    /**
     * @param attrsJson attrs
     * @param name      整数字段名
     * @return 没有该字段时为 null
     */
    public static Integer intField(String attrsJson, String name) {
        JsonNode value = fieldOf(attrsJson, name);
        if (value == null || !value.isNumber()) {
            return null;
        }
        return value.asInt();
    }

    /**
     * @param attrsJson attrs
     * @return truncated；没有该字段时为 null
     */
    public static Boolean truncated(String attrsJson) {
        JsonNode value = fieldOf(attrsJson, "truncated");
        if (value == null || !value.isBoolean()) {
            return null;
        }
        return value.asBoolean();
    }

    /**
     * 取一个字符串字段（公开读口，供面板展示人话摘要类字段）。
     *
     * @param attrsJson attrs_json
     * @param name      键名（不带引号），如 {@code prompt_breakdown}
     * @return 字段值；没有该字段时为 null
     */
    public static String textField(String attrsJson, String name) {
        return textOf(attrsJson, name);
    }

    /**
     * 取一个字符串字段。
     *
     * @param attrsJson attrs_json
     * @param name      键名（不带引号），如 {@code brief}
     * @return 字段值；没有或非字符串时为空
     */
    private static String textOf(String attrsJson, String name) {
        JsonNode value = fieldOf(attrsJson, name);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.isEmpty() ? null : text;
    }

    /**
     * 取字段节点。解析失败按「没有该字段」处理：attrs 是旁路信息，
     * 读不出来不该让整条追踪查询失败。
     */
    private static JsonNode fieldOf(String attrsJson, String name) {
        ObjectNode node = parseOrNull(attrsJson);
        return node == null ? null : node.get(name);
    }

    /**
     * 把一个字段写进 attrs。
     *
     * @param existing 已有 attrs
     * @param key      字段名（不带引号）
     * @param value    新值
     * @return 合并后的 JSON
     */
    private static String merge(String existing, String key, JsonNode value) {
        ObjectNode node = objectOf(existing);
        node.set(key, value);
        return node.toString();
    }

    /**
     * @return 可写的对象节点；原 attrs 不是 JSON 对象时从空对象起步（旁路信息不值得为此报错）
     */
    private static ObjectNode objectOf(String existing) {
        ObjectNode parsed = parseOrNull(existing);
        return parsed == null ? JSON.createObjectNode() : parsed;
    }

    /**
     * @return 解析结果；空/非法/非对象时返回 null
     */
    private static ObjectNode parseOrNull(String attrsJson) {
        if (attrsJson == null || attrsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(attrsJson);
            return node instanceof ObjectNode object ? object : null;
        } catch (Exception ex) {
            // 历史脏值不应中断查询链路：只记类型，不把内容打进日志
            log.debug("attrs JSON 不可解析 type={}", ex.getClass().getSimpleName());
            return null;
        }
    }
}

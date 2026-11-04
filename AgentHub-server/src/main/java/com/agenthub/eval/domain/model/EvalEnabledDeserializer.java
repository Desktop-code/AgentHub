package com.agenthub.eval.domain.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * 用例 enabled 宽松反序列化。所属域：eval。
 * <p>定稿与业务套件常写 {@code true}/{@code false}，库与领域模型是 1/0。
 * 若只认 Integer，Jackson 在 Controller {@code @RequestBody} 阶段直接
 * {@code HttpMessageNotReadableException}，全局处理器只会回「请求体格式不正确」，
 * 用户误以为「JSON 格式错误」。本反序列化器与样例导入路径对齐：布尔与整型双认。
 */
public class EvalEnabledDeserializer extends JsonDeserializer<Integer> {

    /**
     * @param p    解析器
     * @param ctxt 上下文
     * @return 1 启用 / 0 停用；null 表示缺省（由 {@code EvalSpecs} 补 1）
     */
    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? EvalSuite.ENABLED : EvalSuite.DISABLED;
        }
        if (node.isIntegralNumber()) {
            int value = node.intValue();
            if (value == EvalSuite.ENABLED || value == EvalSuite.DISABLED) {
                return value;
            }
            return (Integer) ctxt.handleWeirdNumberValue(Integer.class, value,
                    "enabled 只能是 0/1 或 true/false");
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (text.isEmpty()) {
                return null;
            }
            if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
                return EvalSuite.ENABLED;
            }
            if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
                return EvalSuite.DISABLED;
            }
            return (Integer) ctxt.handleWeirdStringValue(Integer.class, text,
                    "enabled 只能是 0/1 或 true/false");
        }
        return (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
    }
}

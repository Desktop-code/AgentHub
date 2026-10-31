package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.infra.agentscope.builtin.BuiltinParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 BUILTIN 的 {@code @Tool}/{@code @ToolParam} 生成 JSON Schema。所属域：runtime 的 infra。
 * 模型侧以这里的结果为准：库表空或旧文都不能把参数约束弱化。
 * 无参工具输出 properties 为空对象，与种子 SQL 一致。
 */
public final class BuiltinToolSchemas {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long UNSET_MIN = Long.MIN_VALUE;
    private static final long UNSET_MAX = Long.MAX_VALUE;

    private BuiltinToolSchemas() {
    }

    /**
     * @param bean     带 {@code @Tool} 的实现，允许 Spring 代理
     * @param toolName {@code @Tool(name)}，与 tool_item.name 一致
     * @return 紧凑 JSON；找不到该方法时为 null
     */
    public static String jsonOf(Object bean, String toolName) {
        return jsonOf(userClass(bean), toolName);
    }

    /**
     * @param type     实现类，不用代理类
     * @param toolName {@code @Tool(name)}
     * @return 紧凑 JSON；找不到该方法时为 null
     */
    public static String jsonOf(Class<?> type, String toolName) {
        Map<String, Object> parameters = parametersOf(type, toolName);
        if (parameters == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(parameters);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("内置工具 schema 无法序列化: " + toolName, ex);
        }
    }

    /**
     * @param bean     带 {@code @Tool} 的实现
     * @param toolName 调用名
     * @return 送给模型的 parameters；找不到时为 null
     */
    public static Map<String, Object> parametersOf(Object bean, String toolName) {
        return parametersOf(userClass(bean), toolName);
    }

    /**
     * @param type     实现类
     * @param toolName 调用名
     * @return 送给模型的 parameters；找不到时为 null
     */
    public static Map<String, Object> parametersOf(Class<?> type, String toolName) {
        Method method = find(type, toolName);
        if (method == null) {
            return null;
        }
        return schemaOf(method);
    }

    /**
     * 只收集带 {@code @ToolParam} 的参数。AgentScope 同样忽略没标的参数，两边口径一致。
     */
    private static Map<String, Object> schemaOf(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter param : method.getParameters()) {
            ToolParam toolParam = param.getAnnotation(ToolParam.class);
            if (toolParam == null || toolParam.name() == null || toolParam.name().isBlank()) {
                continue;
            }
            properties.put(toolParam.name(), propertyOf(param, toolParam));
            if (toolParam.required()) {
                required.add(toolParam.name());
            }
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /**
     * 类型来自 Java 参数，说明来自 {@code @ToolParam}，数值边界来自 {@link BuiltinParam}。
     */
    private static Map<String, Object> propertyOf(Parameter param, ToolParam toolParam) {
        Map<String, Object> property = new LinkedHashMap<>();
        String jsonType = jsonType(param.getType());
        property.put("type", jsonType);
        BuiltinParam bounds = param.getAnnotation(BuiltinParam.class);
        if (bounds != null) {
            if (bounds.minimum() != UNSET_MIN) {
                property.put("minimum", bounds.minimum());
            }
            if (bounds.maximum() != UNSET_MAX) {
                property.put("maximum", bounds.maximum());
            }
            Object defaultValue = defaultValue(bounds.defaultValue(), jsonType);
            if (defaultValue != null) {
                property.put("default", defaultValue);
            }
        }
        if (toolParam.description() != null && !toolParam.description().isBlank()) {
            property.put("description", toolParam.description());
        }
        return property;
    }

    /**
     * 默认值按 JSON 类型落成数字或布尔，避免整数参数在 schema 里变成字符串。
     * 解析失败就省略 default，不让一条脏注解挡住工具注册。
     */
    private static Object defaultValue(String raw, String jsonType) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            if ("integer".equals(jsonType)) {
                return Integer.valueOf(text);
            }
            if ("number".equals(jsonType)) {
                return Double.valueOf(text);
            }
            if ("boolean".equals(jsonType)) {
                return Boolean.valueOf(text);
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return text;
    }

    private static String jsonType(Class<?> type) {
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class
                || type == Short.class || type == short.class) {
            return "integer";
        }
        if (type == Double.class || type == double.class || type == Float.class || type == float.class) {
            return "number";
        }
        return "string";
    }

    /**
     * 沿声明类向上找同名 {@code @Tool}。代理类本身没有注解，调用方应先解开代理。
     */
    private static Method find(Class<?> type, String toolName) {
        if (type == null || toolName == null || toolName.isBlank()) {
            return null;
        }
        String expected = toolName.trim();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Tool annotation = method.getAnnotation(Tool.class);
                if (annotation == null) {
                    continue;
                }
                String name = annotation.name() == null || annotation.name().isBlank()
                        ? method.getName() : annotation.name();
                if (expected.equals(name)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Class<?> userClass(Object bean) {
        if (bean == null) {
            return null;
        }
        return ClassUtils.getUserClass(bean);
    }
}

package com.agenthub.runtime.domain.service;

import java.util.Optional;

/**
 * BUILTIN 的 Java schema 出口。所属域：runtime。
 * 实现放在 infra，按 {@code @Tool} 现算；没有对应实现时返回 empty，调用方再退回库表。
 */
public interface BuiltinSchemaSource {

    /**
     * BUILTIN 注入时 Java 优先。没有实现时退回库表；两边都空则仍是 null，提示词按占位成本暴露。
     *
     * @param dbSchema   tool_item.input_schema_json，可空
     * @param javaSchema 注解生成的 schema，可空
     * @return 实际计入预算的 schema
     */
    static String prefer(String dbSchema, String javaSchema) {
        if (javaSchema != null && !javaSchema.isBlank()) {
            return javaSchema;
        }
        return dbSchema;
    }

    /**
     * @param toolName tool_item.name，与 {@code @Tool(name)} 一致
     * @return 紧凑 JSON Schema；无实现或名为空时 empty
     */
    Optional<String> jsonFor(String toolName);
}

package com.agenthub.runtime.model.acl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一工具定义。所属域：runtime（模型出站 ACL）。
 * 业务侧只提供名字 + 说明 + JSON Schema，Adapter 负责把它摆到 tools / functions / Anthropic tools 等位置。
 * 注意：是否暴露某工具由 runtime 的 ToolEnablement 决定，本类不做启用判定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedToolDef {

    /** 模型侧调用名，需全局唯一且符合厂商命名限制（字母数字下划线中划线） */
    private String name;

    /** 一句话说明，供模型判断何时调用 */
    private String description;

    /**
     * 参数 JSON Schema 原文（object 结构）。
     * 存字符串而不是解析后的 Map：避免各 Adapter 共享同一可变结构，
     * 也便于直接把 tool_item.input_schema_json 透传；需要时由 Adapter 自行解析。
     */
    private String parametersJsonSchema;
}

package com.agenthub.runtime.model.acl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一工具调用。所属域：runtime（模型出站 ACL）。
 * 定稿约定：arguments 保持「原始 JSON 字符串」，不在此处解析成对象；
 * 需要结构化参数时由调用方按 id 自行解析并缓存，避免各层各自解析出不一致结果。
 * 流式场景下参数是分片到达的，只有 tool_call.completed 事件里的本对象才是完整可执行参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedToolCall {

    /** 本次调用的关联 id；回灌 tool 消息时用它对齐，禁止用下标对齐 */
    private String id;

    /** 工具名，需与 UnifiedToolDef.name 一致 */
    private String name;

    /** 参数原始 JSON 字符串（可能是不完整分片，取决于所在事件类型） */
    private String arguments;
}

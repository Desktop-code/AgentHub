package com.agenthub.runtime.model.acl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一对话消息。所属域：runtime（模型出站 ACL）。
 * 业务侧永远按「role + content + 工具信息」表达一轮消息，System 放顶层还是首条消息、
 * Anthropic content blocks 等差异全部在 Adapter 内部消化。
 * 不可变约定：本类是可读写的 POJO（Lombok），跨 Adapter 传递前请自行保证不被并发改写。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedMessage {

    /**
     * 角色。只允许四个稳定取值（与 OpenAI 语义对齐，其余厂商由 Adapter 映射）：
     * system / user / assistant / tool。
     */
    private String role;

    /**
     * 正文。纯文本直传；多段（图片、多 modality）后续用 extensions 表达，
     * 本期不做 content 分段模型，避免过早定死结构。
     */
    private String content;

    /**
     * 助手消息里模型发起的工具调用。仅有工具调用（无正文）的 assistant 消息 content 可为空。
     */
    @Builder.Default
    private List<UnifiedToolCall> toolCalls = new ArrayList<>();

    /**
     * role=tool 时对应哪一次工具调用（回填结果的关联键），其它角色为 null。
     */
    private String toolCallId;

    /**
     * 可选的发言人名，对应部分厂商的 name 字段；不参与路由判定。
     */
    private String name;

    /**
     * 便捷构造：系统提示词消息。
     *
     * @param text 系统提示词正文
     * @return role=system 的消息
     */
    public static UnifiedMessage system(String text) {
        return UnifiedMessage.builder().role("system").content(text).build();
    }

    /**
     * 便捷构造：用户输入消息。
     *
     * @param text 用户本轮输入
     * @return role=user 的消息
     */
    public static UnifiedMessage user(String text) {
        return UnifiedMessage.builder().role("user").content(text).build();
    }

    /**
     * 便捷构造：工具结果回灌消息。
     *
     * @param toolCallId 上一次 assistant 发起的工具调用 id
     * @param text       工具执行结果（已脱敏、已截断）
     * @return role=tool 的消息
     */
    public static UnifiedMessage tool(String toolCallId, String text) {
        return UnifiedMessage.builder().role("tool").toolCallId(toolCallId).content(text).build();
    }
}

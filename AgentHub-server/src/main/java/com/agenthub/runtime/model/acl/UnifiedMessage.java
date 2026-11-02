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
     * 正文。纯文本直传，始终保留：单模态场景（绝大多数历史消息、工具结果）只填本字段，
     * Adapter 看到 imageUrls 为空时一律按字符串下发 content，保持与旧行为逐字节一致。
     */
    private String content;

    /**
     * 随本条消息一起送出的图片，元素是「URL」或「data:{mime};base64,...」。
     * 为什么用最简的字符串列表而不是分段的 ContentPart 集合：
     * 1. 入参只有「一段文字 + N 张图」这一种形态，出参永远是纯文本，没有必要提前把结构做通用；
     * 2. base64 图片可能达到数 MB，独立成字段便于在边界上做长度校验与日志脱敏（正文里绝不出现）；
     * 3. 厂商差异（{@code image_url} / {@code image} / Anthropic base64 block）全部下沉到 Adapter。
     * 约束：本字段只允许出现在 role=user 的消息上；system / tool 带图会被各 Adapter 忽略。
     */
    @Builder.Default
    private List<String> imageUrls = new ArrayList<>();

    /**
     * 是否携带图片。Adapter 用它决定 content 走字符串还是分段数组。
     *
     * @return 至少一张非空白图片时为 true
     */
    public boolean hasImages() {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return false;
        }
        for (String url : imageUrls) {
            if (url != null && !url.isBlank()) {
                return true;
            }
        }
        return false;
    }

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
     * 便捷构造：图文混排的用户消息。
     *
     * @param text      用户本轮文字提问，可空（只发图时 Adapter 会补一段空文本占位）
     * @param imageUrls 图片 URL 或 base64 data URI，空元素会被 Adapter 跳过
     * @return role=user 的消息
     */
    public static UnifiedMessage user(String text, List<String> imageUrls) {
        return UnifiedMessage.builder()
                .role("user")
                .content(text)
                .imageUrls(imageUrls == null ? new ArrayList<>() : new ArrayList<>(imageUrls))
                .build();
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

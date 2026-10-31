package com.agenthub.runtime.domain.model;

/**
 * 本轮已注入系统提示的一条长期记忆。所属域：runtime。
 * 只承载 fact_key 与正文，供工具必填参数补全使用；正文写入侧已拦过密钥，这里不再二次处理。
 * 只包含本 Agent 已开启长期记忆且本轮真正注入的条目，未注入的记忆不得参与补参。
 *
 * @param factKey 已归一的稳定事实键（如 residence_city），可空
 * @param title   记忆标题，可空
 * @param content 记忆正文，可空
 */
public record MemoryFact(String factKey, String title, String content) {

    /**
     * @return 供参数回溯匹配的正文（标题 + 正文），不含空段
     */
    public String searchable() {
        StringBuilder text = new StringBuilder();
        if (title != null && !title.isBlank()) {
            text.append(title.trim()).append(' ');
        }
        if (content != null && !content.isBlank()) {
            text.append(content.trim());
        }
        return text.toString();
    }
}

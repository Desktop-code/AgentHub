package com.agenthub.runtime.domain.model;

import lombok.Data;

/**
 * 一轮提示词实际注入了多少。所属域：runtime。
 * 写入 LLM span 的 attrs，右栏用来判断有没有截断。
 * truncated 为任一截断；promptSegmentTruncated / toolSchemaBudgetHit 区分「其它段」与「工具额度」。
 */
@Data
public class PromptInjectionStats {

    private int toolsInjected;
    private int skillsInjected;
    /** 其中被触发关键词硬注入（系统强制，不经模型判断）的 Skill 个数 */
    private int skillsHardTriggered;
    private int systemChars;
    private int skillChars;
    private int toolCatalogChars;
    private int toolSchemaChars;
    /** 系统/Skill/目录行任一截断，或工具 schema 预算命中 */
    private boolean truncated;
    /** 平台前缀/业务段、Skill 或目录行本身被截断（与工具是否暴露无关） */
    private boolean promptSegmentTruncated;
    /** 可调用工具因 schema 预算不足而未暴露 */
    private boolean toolSchemaBudgetHit;

    /**
     * @return 各类字符合计，和窗口、工具回灌分开计
     */
    public int totalChars() {
        return systemChars + skillChars + toolCatalogChars + toolSchemaChars;
    }
}

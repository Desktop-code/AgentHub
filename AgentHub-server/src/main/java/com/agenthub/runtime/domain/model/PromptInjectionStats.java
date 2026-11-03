package com.agenthub.runtime.domain.model;

import lombok.Data;

/**
 * 一轮提示词实际注入了多少、花在哪一段。所属域：runtime。
 * <p>
 * 为什么按段记账：只看总字符数无法回答「钱花在哪」。企业级裁剪要求先说清
 * 「保护区（L1+锁定段）占多少、可压区占多少、Skill / 工具目录 / schema 各占多少」，
 * 再决定砍谁；因此每段都同时给<b>字符数</b>与<b>估算 token</b>。
 * <p>
 * token 是出站前的保守估算（{@link com.agenthub.common.text.TokenEstimator}），
 * 精确值只有上游 usage 返回后才知道；两者用途不同：估算用于预算与占比，usage 用于计费。
 * <p>
 * 写入 LLM span 的 attrs（见 {@code PromptInjectionAttrs} + {@code ObsAttrs.withFields}），
 * 面板据此判断有没有截断、哪段最贵。
 * 截断标记分三类：{@link #truncated}（任一）/ {@link #promptSegmentTruncated}（非工具额度）/ {@link #toolSchemaBudgetHit}（工具额度）。
 */
@Data
public class PromptInjectionStats {

    // ---------------- 计数 ----------------

    private int toolsInjected;
    private int skillsInjected;
    /** 其中被触发关键词硬注入（系统强制，不经模型判断）的 Skill 个数 */
    private int skillsHardTriggered;
    /** 被硬上限截断正文的强制 Skill 个数；>0 表示强制规则没能整篇进上下文 */
    private int forcedSkillsClipped;

    // ---------------- 系统提示分段（字符） ----------------

    /** 组装正文（保护区 + 可压区）最终字符数，不含记忆 / Skill / 目录 / user */
    private int systemChars;
    /** 受保护前缀字符数（L1 + 平台追加句 + 锁定段）；任何裁剪都不得低于该长度 */
    private int protectedChars;
    /** 可压区字符数（开放段 + L3 追加 + 历史业务段）＝ systemChars − protectedChars */
    private int systemSoftChars;
    /** 记忆段（工作记忆 + 用户偏好）实际注入字符数；被预算让出时为 0 */
    private int memoryChars;
    /** Skill 段合计字符数（强制 + 按需） */
    private int skillChars;
    /** 强制 Skill 块字符数（段头 + 场景 + 固定前缀 + 正文） */
    private int skillRequiredChars;
    /** 按需 Skill 块字符数（默认只有段头 + 场景） */
    private int skillOptionalChars;
    /** 工具目录字符数（名称 + 一句话） */
    private int toolCatalogChars;
    /** 本轮可调用工具 schema 字符数（走 tools 字段，不进 system） */
    private int toolSchemaChars;
    /** 本轮用户侧消息字符数（【会话摘要】/【最近对话】/【短期记忆】/本轮原文） */
    private int userChars;
    /** 其中【会话摘要】段字符数 */
    private int userSummaryChars;
    /** 其中【最近对话】段字符数 */
    private int userRecentChars;
    /** 其中【短期记忆】段字符数 */
    private int userShortChars;
    /** 其中本轮用户原文段字符数 */
    private int userCurrentChars;
    /** 本轮被落盘成文件的工具结果条数 */
    private int toolResultsOffloaded;
    /** 被落盘工具结果的原始正文合计字符数（用来证明省了多少） */
    private int toolResultsOffloadedChars;

    // ---------------- 系统提示分段（估算 token） ----------------

    private int protectedTokens;
    private int systemSoftTokens;
    private int memoryTokens;
    private int skillRequiredTokens;
    private int skillOptionalTokens;
    private int toolCatalogTokens;
    private int toolSchemaTokens;
    private int userTokens;

    // ---------------- 状态 ----------------

    /** 系统/Skill/目录行任一截断，或工具 schema 预算命中 */
    private boolean truncated;
    /** 平台前缀/业务段、Skill 或目录行本身被截断（与工具是否暴露无关） */
    private boolean promptSegmentTruncated;
    /** 可调用工具因 schema 预算不足而未暴露 */
    private boolean toolSchemaBudgetHit;

    /**
     * 记录本轮用户侧消息体量。由执行侧在拿到真正要送进模型的用户文本后调用，
     * 装配侧拿不到这段正文，因此单独留一个入口。
     * <p>
     * 除了总长度，还按【会话摘要】/【最近对话】/【短期记忆】/【当前用户】分段记账：
     * P1 的核心问题就是「摘要和近轮是不是把同一段写了两遍」，只看总数答不出来。
     *
     * @param userText 送给模型的用户侧文本，可空
     */
    public void meterUser(String userText) {
        String text = userText == null ? "" : userText;
        this.userChars = text.length();
        this.userTokens = com.agenthub.common.text.TokenEstimator.estimate(text);
        com.agenthub.runtime.domain.service.UserPromptSections.Parts parts =
                com.agenthub.runtime.domain.service.UserPromptSections.split(text);
        this.userSummaryChars = parts.summary();
        this.userRecentChars = parts.recent();
        this.userShortChars = parts.shorts();
        this.userCurrentChars = parts.current();
    }

    /**
     * 记一条已落盘的工具结果。
     * <p>
     * 只累计条数与原始字符数：回灌用的是摘要，但「原本有多少字」才是判断这次省了多少的依据。
     *
     * @param rawChars 被落盘的原始正文长度
     */
    public void meterOffloadedToolResult(int rawChars) {
        this.toolResultsOffloaded++;
        this.toolResultsOffloadedChars += Math.max(rawChars, 0);
    }

    /**
     * @return 各类字符合计（system + 记忆 + Skill + 目录 + schema + user），与窗口、工具回灌分开计
     */
    public int totalChars() {
        return systemChars + memoryChars + skillChars + toolCatalogChars + toolSchemaChars + userChars;
    }

    /**
     * @return 各类估算 token 合计，口径与 {@link #totalChars()} 一致
     */
    public int totalTokens() {
        return protectedTokens + systemSoftTokens + memoryTokens
                + skillRequiredTokens + skillOptionalTokens
                + toolCatalogTokens + toolSchemaTokens + userTokens;
    }

    /**
     * 分段占比的人话摘要，直接写进 attrs 供面板原样展示。
     * <p>
     * 为什么用字符串而不是嵌套 JSON：attrs 的读取口径是「扁平键 + 简单字符串」，
     * 面板只做展示不做计算，压成一行比嵌套对象更省事也更好读。
     * <p>
     * Skill 段后面额外带「强制 N 条」与「截断 N 条」：光看字数无法判断
     * 「这条规则是完整的还是被砍过的」，而后者直接影响模型行为。
     *
     * @return 形如「保护区 1200字/1200t · 可压区 300字/300t · 记忆 200字/200t
     *         · Skill 260字/260t（强制 1 条 180字）· 目录 300字/300t · schema 5000字/5000t
     *         · 用户 100字/100t」
     */
    public String breakdown() {
        StringBuilder text = new StringBuilder();
        appendPart(text, "保护区", protectedChars, protectedTokens);
        appendPart(text, "可压区", systemSoftChars, systemSoftTokens);
        appendPart(text, "记忆", memoryChars, memoryTokens);
        String skill = "Skill " + (skillRequiredChars + skillOptionalChars) + "字/"
                + (skillRequiredTokens + skillOptionalTokens) + "t"
                + "（强制 " + skillsHardTriggered + " 条 " + skillRequiredChars + "字"
                + (forcedSkillsClipped > 0 ? "，截断 " + forcedSkillsClipped + " 条" : "") + "）";
        if (text.length() > 0) {
            text.append(" · ");
        }
        text.append(skill);
        appendPart(text, "目录", toolCatalogChars, toolCatalogTokens);
        appendPart(text, "schema", toolSchemaChars, toolSchemaTokens);
        // 用户侧再拆开：P1 要回答「摘要 / 近轮 / 工具句柄各占多少」，只给合计看不出问题
        appendPart(text, "用户", userChars, userTokens);
        if (userChars > 0) {
            text.append("（摘要 ").append(userSummaryChars).append(" · 近轮 ").append(userRecentChars)
                    .append(" · 短期 ").append(userShortChars).append(" · 本轮 ").append(userCurrentChars)
                    .append('）');
        }
        if (toolResultsOffloaded > 0) {
            text.append(" · 工具句柄 ").append(toolResultsOffloaded).append(" 条/原 ")
                    .append(toolResultsOffloadedChars).append("字");
        }
        return text.toString();
    }

    /**
     * 拼一段「名称 字数/估t」，空段也照写，读者能确认「这段确实是 0」而不是漏统计。
     */
    private static void appendPart(StringBuilder text, String label, int chars, int tokens) {
        if (text.length() > 0) {
            text.append(" · ");
        }
        text.append(label).append(' ').append(chars).append('字').append('/').append(tokens).append('t');
    }
}
